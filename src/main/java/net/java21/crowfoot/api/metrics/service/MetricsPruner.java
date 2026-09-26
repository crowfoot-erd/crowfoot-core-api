package net.java21.crowfoot.api.metrics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.metrics.repository.VisitEventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/**
 * 방문 이벤트 보존 정리 배치 (08-core/10-metrics.md Section 5.3) — 매일 04:30 KST에
 * date_kst가 90일 초과인 원본 행을 삭제한다. 롤업(daily_metric_rollups)은 지표의 원천이라
 * 삭제하지 않는다. 삭제 건수는 감사(STATS_EVENTS_PRUNED, 주체 NULL=시스템)로 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsPruner {

    static final int RETENTION_DAYS = 90;

    private final VisitEventRepository visitEventRepository;
    private final AuditRecorder auditRecorder;

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void prune() {
        LocalDate cutoff = LocalDate.now(VisitMetricsService.KST).minusDays(RETENTION_DAYS);
        long deleted = visitEventRepository.deleteByDateKstBefore(cutoff);
        log.info("방문 이벤트 보존 정리 — cutoff 미만({}) {}건 삭제", cutoff, deleted);
        auditRecorder.record(null, "STATS_EVENTS_PRUNED", "METRICS", "ALL",
                Map.of("deleted", deleted, "before", cutoff.toString()));
    }
}
