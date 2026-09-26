package net.java21.crowfoot.api.metrics.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/** 관리자 통계 조회 테스트 (08-core/10-metrics.md Section 7) — 게이트·검증·UUV 3차원 합산·TOP 20. */
@ExtendWith(MockitoExtension.class)
class AdminMetricsServiceTest {

    private static final long ADMIN = 1L;

    @Mock
    private DailyMetricRollupRepository rollupRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private ModelShareRepository shareRepository;

    private AdminMetricsService service;

    @BeforeEach
    void setUp() {
        service = new AdminMetricsService(rollupRepository, auditLogRepository, adminGuard, auditRecorder,
                shareRepository);
    }

    @Test
    @DisplayName("days는 7·28·90만 — 그 외는 INVALID_REQUEST(detail.metrics.days)")
    void invalidDaysIsRejected() {
        assertThatThrownBy(() -> service.summary(ADMIN, 30))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ex.getMessageKey()).isEqualTo("detail.metrics.days");
                });
    }

    @Test
    @DisplayName("breakdown dimension은 열거 화이트리스트만 — 그 외는 INVALID_REQUEST(detail.metrics.dimension)")
    void invalidDimensionIsRejected() {
        assertThatThrownBy(() -> service.breakdown(ADMIN, "visitor_uuid", 7))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ex.getMessageKey()).isEqualTo("detail.metrics.dimension");
                });
    }

    @Test
    @DisplayName("비관리자 조회는 PERMISSION_DENIED로 거부된다")
    void nonAdminIsRejected() {
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .given(adminGuard).requireAdmin(anyLong());

        assertThatThrownBy(() -> service.summary(99L, 7))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("요약 — UUV는 visitor_new+returning+estimated 3차원 합, 신규 방문자는 visitor_new, 계열의 빈 날은 0")
    void summaryAggregatesUuvDimensions() {
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        List<DailyMetricRollup> rows = List.of(
                new DailyMetricRollup(today, "pv", "all", 10),
                new DailyMetricRollup(today, "visitor_new", "all", 3),
                new DailyMetricRollup(today, "visitor_returning", "all", 2),
                new DailyMetricRollup(today, "visitor_estimated", "all", 1),
                new DailyMetricRollup(today, "session", "all", 6),
                new DailyMetricRollup(today, "bot", "all", 4),
                new DailyMetricRollup(today.minusDays(1), "pv", "all", 8),
                new DailyMetricRollup(today.minusDays(1), "visitor_new", "all", 2),
                // 분포 차원(country 등)은 요약에 합산되지 않는다
                new DailyMetricRollup(today, "country", "KR", 9));
        given(rollupRepository.findByDateKstBetweenOrderByDateKstAsc(any(), any())).willReturn(rows);

        MetricsSummaryResponse response = service.summary(ADMIN, 7);

        assertThat(response.days()).isEqualTo(7);
        assertThat(response.series()).hasSize(7);
        MetricsSummaryResponse.DailyPoint todayPoint = response.series().get(6);
        assertThat(todayPoint.date()).isEqualTo(today.toString());
        assertThat(todayPoint.pv()).isEqualTo(10);
        assertThat(todayPoint.uuv()).isEqualTo(6);          // 3(new)+2(returning)+1(estimated)
        assertThat(todayPoint.sessions()).isEqualTo(6);
        assertThat(todayPoint.newVisitors()).isEqualTo(3);
        MetricsSummaryResponse.DailyPoint yesterdayPoint = response.series().get(5);
        assertThat(yesterdayPoint.pv()).isEqualTo(8);
        assertThat(yesterdayPoint.uuv()).isEqualTo(2);
        // 스냅샷 3종 — 오늘·어제·전주 동일 요일(빈 날은 0)
        assertThat(response.today().uuv()).isEqualTo(6);
        assertThat(response.yesterday().pv()).isEqualTo(8);
        assertThat(response.lastWeekSameDay().pv()).isZero();
        // 조회 감사
        verify(auditRecorder).record(ADMIN, "ADMIN_METRICS_VIEWED", "METRICS", "ALL",
                java.util.Map.of("view", "summary", "days", 7));
    }

    @Test
    @DisplayName("차원별 분포 — 지정 dimension만 합산해 내림차순 TOP, share는 소수 4자리")
    void breakdownSortsAndLimits() {
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        List<DailyMetricRollup> rows = List.of(
                new DailyMetricRollup(today, "browser", "chrome", 12),
                new DailyMetricRollup(today.minusDays(1), "browser", "chrome", 8),
                new DailyMetricRollup(today, "browser", "safari", 3),
                new DailyMetricRollup(today, "browser", "edge", 2),
                new DailyMetricRollup(today, "country", "KR", 50));   // 다른 차원은 무시
        given(rollupRepository.findByDateKstBetweenOrderByDateKstAsc(any(), any())).willReturn(rows);

        MetricsBreakdownResponse response = service.breakdown(ADMIN, "browser", 7);

        assertThat(response.dimension()).isEqualTo("browser");
        assertThat(response.total()).isEqualTo(25);
        assertThat(response.entries()).hasSize(3);
        assertThat(response.entries().get(0).key()).isEqualTo("chrome");
        assertThat(response.entries().get(0).count()).isEqualTo(20);
        assertThat(response.entries().get(0).share()).isEqualTo(0.8);
        assertThat(response.entries().get(1).key()).isEqualTo("safari");
        assertThat(response.entries().get(2).key()).isEqualTo("edge");
    }

    @Test
    @DisplayName("share 차원 — key는 토큰 그대로, 화면 표기명(displayName)은 문서명으로 조인해 내려준다")
    void shareBreakdownResolvesDocumentNames() {
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        given(rollupRepository.findByDateKstBetweenOrderByDateKstAsc(any(), any())).willReturn(List.of(
                new DailyMetricRollup(today, "share", "tok123", 30),
                new DailyMetricRollup(today, "share", "tok456", 10)));
        given(shareRepository.findDocumentNamesByTokens(List.of("tok123", "tok456")))
                .willReturn(List.<Object[]>of(new Object[]{"tok123", "쇼핑몰 ERD"}));

        MetricsBreakdownResponse response = service.breakdown(ADMIN, "share", 7);

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(0).key()).isEqualTo("tok123");
        assertThat(response.entries().get(0).displayName()).isEqualTo("쇼핑몰 ERD");
        assertThat(response.entries().get(1).key()).isEqualTo("tok456");
        assertThat(response.entries().get(1).displayName()).isNull();   // 문서가 사라진 공유 — 웹이 토큰 폴백
    }

    @Test
    @DisplayName("활동 — 일별 로그인(성공/실패)은 계열의 모든 날짜를 0으로 채우고, 액션은 내림차순 총수")
    void activityFillsEmptyDays() {
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        String yesterday = today.minusDays(1).toString();
        given(auditLogRepository.countDailyLogins(any(Instant.class))).willReturn(List.of(
                new Object[]{yesterday, "LOGIN_SUCCEEDED", 5L},
                new Object[]{yesterday, "LOGIN_FAILED", 2L}));
        given(auditLogRepository.countByAction(any(Instant.class))).willReturn(List.of(
                new Object[]{"MODEL_CREATED", 10L},
                new Object[]{"LOGIN_SUCCEEDED", 7L}));

        MetricsActivityResponse response = service.activity(ADMIN, 7);

        assertThat(response.days()).isEqualTo(7);
        assertThat(response.logins()).hasSize(7);
        MetricsActivityResponse.LoginDay yesterdayLogin = response.logins().get(5);
        assertThat(yesterdayLogin.date()).isEqualTo(yesterday);
        assertThat(yesterdayLogin.succeeded()).isEqualTo(5);
        assertThat(yesterdayLogin.failed()).isEqualTo(2);
        assertThat(response.logins().get(6).succeeded()).isZero();   // 오늘 — 데이터 없음 → 0
        assertThat(response.actions()).hasSize(2);
        assertThat(response.actions().get(0).action()).isEqualTo("MODEL_CREATED");
        assertThat(response.actions().get(0).count()).isEqualTo(10);
    }
}
