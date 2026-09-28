package net.java21.crowfoot.api.notification.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.notification.domain.Notification;
import net.java21.crowfoot.api.notification.repository.NotificationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 INSERT — 별도 트랜잭션(REQUIRES_NEW)으로만 기록한다(AuditLogWriter와 같은 규칙).
 *
 * <p>호출 규칙: 반드시 {@link NotificationRecorder} 경유 — 이 컴포넌트가 직접 던지는 예외는
 * 새 트랜잭션 롤백으로 끝나므로 본류(댓글·반응 등록)를 오염시키지 않는다.
 */
@Component
@RequiredArgsConstructor
public class NotificationWriter {

    private final NotificationRepository notificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Notification notification) {
        notificationRepository.save(notification);
    }
}
