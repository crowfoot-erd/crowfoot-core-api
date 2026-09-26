package net.java21.crowfoot.api.metrics.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.repository.AuditLogRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.metrics.domain.DailyMetricRollup;
import net.java21.crowfoot.api.metrics.dto.MetricsActivityResponse;
import net.java21.crowfoot.api.metrics.dto.MetricsBreakdownResponse;
import net.java21.crowfoot.api.metrics.dto.MetricsSummaryResponse;
import net.java21.crowfoot.api.metrics.repository.DailyMetricRollupRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 관리자 트래픽 통계 조회 (08-core/10-metrics.md Section 7) — 통계 화면은 롤업 테이블만 읽는다.
 * 원본(visit_events)을 직접 집계하지 않는다(90일 보존 — 원본이 사라져도 지표는 유지).
 * 조회마다 감사(ADMIN_METRICS_VIEWED)로 남는다.
 */
@Service
@RequiredArgsConstructor
public class AdminMetricsService {

    /** 기간 프리셋 — 이 외 days는 400 */
    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 28, 90);

    /** breakdown이 열람 가능한 차원(롤업 dimension 열거 중 분포 표현이 유의미한 것만) */
    private static final Set<String> BREAKDOWN_DIMENSIONS =
            Set.of("country", "browser", "os", "device", "lang", "referrer", "page", "share");

    private static final int TOP = 20;

    private final DailyMetricRollupRepository rollupRepository;
    private final AuditLogRepository auditLogRepository;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;
    private final ModelShareRepository shareRepository;

    /** 요약 — 오늘·어제·전주 동일 요일 스냅샷 + 일별 계열(days 일). UUV는 3차원 합(Section 5.2) */
    @Transactional(readOnly = true)
    public MetricsSummaryResponse summary(long adminId, Integer days) {
        adminGuard.requireAdmin(adminId);
        int validated = validateDays(days);
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        LocalDate seriesFrom = today.minusDays(validated - 1L);
        // 전주 동일 요일 스냅샷(오늘-7)은 days=7일 때 계열 밖이다 — 한 주 더 앞까지 조회한다
        Map<LocalDate, DayStats> byDate = loadStats(seriesFrom.minusDays(7), today);

        List<MetricsSummaryResponse.DailyPoint> series = new ArrayList<>(validated);
        for (LocalDate date = seriesFrom; !date.isAfter(today); date = date.plusDays(1)) {
            DayStats stats = byDate.getOrDefault(date, DayStats.EMPTY);
            series.add(new MetricsSummaryResponse.DailyPoint(
                    date.toString(), stats.pv(), stats.uuv(), stats.sessions(), stats.newVisitors()));
        }
        auditRecorder.record(adminId, "ADMIN_METRICS_VIEWED", "METRICS", "ALL", Map.of("view", "summary", "days", validated));
        return new MetricsSummaryResponse(validated,
                snapshot(byDate, today), snapshot(byDate, today.minusDays(1)), snapshot(byDate, today.minusDays(7)),
                series);
    }

    /** 차원별 분포 — TOP 20 {key, count, share}. 비열거 dimension은 400 */
    @Transactional(readOnly = true)
    public MetricsBreakdownResponse breakdown(long adminId, String dimension, Integer days) {
        adminGuard.requireAdmin(adminId);
        String dim = dimension == null ? "" : dimension.trim();
        if (!BREAKDOWN_DIMENSIONS.contains(dim)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.metrics.dimension");
        }
        int validated = validateDays(days);
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        LocalDate from = today.minusDays(validated - 1L);

        Map<String, Long> counts = new HashMap<>();
        long total = 0;
        for (DailyMetricRollup row : rollupRepository.findByDateKstBetweenOrderByDateKstAsc(from, today)) {
            if (!dim.equals(row.getDimension())) {
                continue;
            }
            counts.merge(row.getMetricKey(), row.getCount(), Long::sum);
            total += row.getCount();
        }
        List<Map.Entry<String, Long>> top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .toList();

        List<MetricsBreakdownResponse.Entry> entries = new ArrayList<>(top.size());
        Map<String, String> documentNames = loadShareDocumentNames(dim, top);
        for (Map.Entry<String, Long> entry : top) {
            double share = total == 0 ? 0
                    : BigDecimal.valueOf(entry.getValue()).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                            .doubleValue();
            entries.add(new MetricsBreakdownResponse.Entry(
                    entry.getKey(), entry.getValue(), share, documentNames.get(entry.getKey())));
        }
        auditRecorder.record(adminId, "ADMIN_METRICS_VIEWED", "METRICS", "ALL",
                Map.of("view", "breakdown", "dimension", dim, "days", validated));
        return new MetricsBreakdownResponse(dim, validated, total, entries);
    }

    /** audit_logs 집계 — 일별 로그인(성공/실패)·액션별 총수(기능 사용량) */
    @Transactional(readOnly = true)
    public MetricsActivityResponse activity(long adminId, Integer days) {
        adminGuard.requireAdmin(adminId);
        int validated = validateDays(days);
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        LocalDate from = today.minusDays(validated - 1L);
        var fromInstant = from.atStartOfDay(VisitMetricsService.KST).toInstant();

        Map<String, long[]> logins = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
            logins.put(date.toString(), new long[2]);
        }
        for (Object[] row : auditLogRepository.countDailyLogins(fromInstant)) {
            long[] pair = logins.computeIfAbsent((String) row[0], k -> new long[2]);
            if ("LOGIN_SUCCEEDED".equals(row[1])) {
                pair[0] += ((Number) row[2]).longValue();
            } else if ("LOGIN_FAILED".equals(row[1])) {
                pair[1] += ((Number) row[2]).longValue();
            }
        }
        List<MetricsActivityResponse.LoginDay> loginDays = logins.entrySet().stream()
                .map(e -> new MetricsActivityResponse.LoginDay(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        List<MetricsActivityResponse.ActionCount> actions = auditLogRepository.countByAction(fromInstant).stream()
                .map(row -> new MetricsActivityResponse.ActionCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();

        auditRecorder.record(adminId, "ADMIN_METRICS_VIEWED", "METRICS", "ALL", Map.of("view", "activity", "days", validated));
        return new MetricsActivityResponse(validated, loginDays, actions);
    }

    private static int validateDays(Integer days) {
        int value = days == null ? 28 : days;
        if (!ALLOWED_DAYS.contains(value)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.metrics.days");
        }
        return value;
    }

    /** share 차원의 토큰→문서명(§5.2 — 화면은 토큰이 아니라 문서명으로 읽는다). 그 외 차원은 빈 맵 */
    private Map<String, String> loadShareDocumentNames(String dim, List<Map.Entry<String, Long>> top) {
        if (!"share".equals(dim) || top.isEmpty()) {
            return Map.of();
        }
        List<String> tokens = top.stream().map(Map.Entry::getKey).toList();
        Map<String, String> names = new HashMap<>();
        for (Object[] row : shareRepository.findDocumentNamesByTokens(tokens)) {
            names.put((String) row[0], (String) row[1]);
        }
        return names;
    }

    private Map<LocalDate, DayStats> loadStats(LocalDate from, LocalDate to) {
        Map<LocalDate, long[]> raw = new HashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            raw.put(date, new long[5]);    // pv, uuv, sessions, newVisitors, bot
        }
        for (DailyMetricRollup row : rollupRepository.findByDateKstBetweenOrderByDateKstAsc(from, to)) {
            long[] bucket = raw.computeIfAbsent(row.getDateKst(), k -> new long[5]);
            switch (row.getDimension()) {
                case "pv" -> bucket[0] += row.getCount();
                // visitor_new는 UUV와 신규 방문자 두 지표에 모두 반영된다
                case "visitor_new" -> {
                    bucket[1] += row.getCount();
                    bucket[3] += row.getCount();
                }
                case "visitor_returning" -> bucket[1] += row.getCount();
                case "visitor_estimated" -> bucket[1] += row.getCount();
                case "session" -> bucket[2] += row.getCount();
                case "bot" -> bucket[4] += row.getCount();
                default -> { /* 국가·브라우저 등 분포 차원 — 요약에 합산하지 않는다 */ }
            }
        }
        Map<LocalDate, DayStats> stats = new HashMap<>();
        raw.forEach((date, bucket) -> stats.put(date,
                new DayStats(bucket[0], bucket[1], bucket[2], bucket[3], bucket[4])));
        return stats;
    }

    private static MetricsSummaryResponse.Snapshot snapshot(Map<LocalDate, DayStats> byDate, LocalDate date) {
        DayStats stats = byDate.getOrDefault(date, DayStats.EMPTY);
        return new MetricsSummaryResponse.Snapshot(
                stats.pv(), stats.uuv(), stats.sessions(), stats.newVisitors(), stats.bot());
    }

    /** 하루치 지표 — 롤업 (dimension=pv 계열) 합 */
    private record DayStats(long pv, long uuv, long sessions, long newVisitors, long bot) {

        static final DayStats EMPTY = new DayStats(0, 0, 0, 0, 0);
    }
}
