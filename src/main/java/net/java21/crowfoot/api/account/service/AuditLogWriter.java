package net.java21.crowfoot.api.account.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.AuditLog;
import net.java21.crowfoot.api.account.repository.AuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 INSERT — 별도 트랜잭션(REQUIRES_NEW)으로만 기록한다.
 *
 * <p>호출 규칙: 반드시 {@link AuditRecorder} 경유 — 이 컴포넌트가 직접 던지는 예외는
 * 새 트랜잭션 롤백으로 끝나므로 본류를 오염시키지 않는다(fail 시 본류 무영향 보장).
 */
@Component
@RequiredArgsConstructor
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Long actorUserId, String action, String targetType, String targetId, String detailJson) {
        auditLogRepository.save(new AuditLog(actorUserId, action, targetType, targetId, detailJson, null));
    }
}
