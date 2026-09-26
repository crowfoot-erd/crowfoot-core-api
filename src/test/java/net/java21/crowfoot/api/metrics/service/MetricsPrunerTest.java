package net.java21.crowfoot.api.metrics.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.metrics.repository.VisitEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** 보존 정리 배치 테스트 (08-core/10-metrics.md Section 5.3) — 90일 경계 삭제·시스템 감사. */
@ExtendWith(MockitoExtension.class)
class MetricsPrunerTest {

    @Mock
    private VisitEventRepository visitEventRepository;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private MetricsPruner pruner;

    @Test
    @DisplayName("KST 오늘-90일 미만 경계로 원본을 삭제하고 삭제 건수를 시스템 감사(actor NULL)로 남긴다")
    void prunesEventsOlderThan90DaysAndAudits() {
        LocalDate cutoff = LocalDate.now(VisitMetricsService.KST).minusDays(MetricsPruner.RETENTION_DAYS);
        given(visitEventRepository.deleteByDateKstBefore(cutoff)).willReturn(7L);

        pruner.prune();

        verify(visitEventRepository).deleteByDateKstBefore(cutoff);
        verify(auditRecorder).record(isNull(), eq("STATS_EVENTS_PRUNED"), eq("METRICS"), eq("ALL"),
                eq(Map.of("deleted", 7L, "before", cutoff.toString())));
    }
}
