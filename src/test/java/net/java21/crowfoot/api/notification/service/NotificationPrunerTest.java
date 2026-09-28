package net.java21.crowfoot.api.notification.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 알림 보존 정리 배치 테스트 (08-core/11-notification.md Section 7) — 90일 경계 삭제·시스템 감사.
 * cutoff는 Instant.now() 기반이라 실행 시점마다 달라져 실행 전후 경계 범위로 검증한다
 * (방문 이벤트 04:30과 분산된 04:40 KST 스케줄).
 */
@ExtendWith(MockitoExtension.class)
class NotificationPrunerTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private NotificationPruner pruner;

    @Test
    @DisplayName("now-90일 경계 미만의 알림을 삭제하고 삭제 건수를 시스템 감사(actor NULL)로 남긴다")
    void prunesNotificationsOlderThan90DaysAndAudits() {
        Instant lower = Instant.now().minus(Duration.ofDays(NotificationPruner.RETENTION_DAYS));
        given(notificationRepository.deleteByCreatedAtBefore(any())).willReturn(7L);

        pruner.prune();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(notificationRepository).deleteByCreatedAtBefore(captor.capture());
        Instant upper = Instant.now().minus(Duration.ofDays(NotificationPruner.RETENTION_DAYS));
        assertThat(captor.getValue()).isBetween(lower.minusSeconds(1), upper.plusSeconds(1)); // 실행 구간 안
        verify(auditRecorder).record(isNull(), eq("NOTIFICATIONS_PRUNED"), eq("NOTIFICATION"), eq("ALL"),
                eq(Map.of("deleted", 7L, "before", captor.getValue().toString())));
    }
}
