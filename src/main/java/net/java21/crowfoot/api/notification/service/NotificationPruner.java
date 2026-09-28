package net.java21.crowfoot.api.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.notification.repository.NotificationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 알림 보존 정리 배치 (08-core/11-notification.md Section 7) — 매일 04:40 KST에 createdAt이
 * 90일 초과인 알림을 삭제한다(방문 이벤트 정리 04:30과 분산 — 같은 시각 배치 집중 회피).
 * 읽음 여부와 무관하게 보존 기한으로만 지운다. 삭제 건수는 감사(NOTIFICATIONS_PRUNED,
 * 주체 NULL=시스템)로 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPruner {

    static final int RETENTION_DAYS = 90;

    private final NotificationRepository notificationRepository;
    private final AuditRecorder auditRecorder;

    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void prune() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(RETENTION_DAYS));
        long deleted = notificationRepository.deleteByCreatedAtBefore(cutoff);
        log.info("알림 보존 정리 — cutoff 미만({}) {}건 삭제", cutoff, deleted);
        auditRecorder.record(null, "NOTIFICATIONS_PRUNED", "NOTIFICATION", "ALL",
                Map.of("deleted", deleted, "before", cutoff.toString()));
    }
}
