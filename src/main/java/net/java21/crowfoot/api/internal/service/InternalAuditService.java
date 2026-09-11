package net.java21.crowfoot.api.internal.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.internal.dto.CreateAuditLogRequest;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 인증 이벤트 감사 기록 내부 API (08-core/05-account.md Section 3.5) — best-effort.
 *
 * <p>계약 요청(actorId·action·detail)을 audit_logs 행으로 옮긴다.
 * targetType/targetId는 계약에 없는 컬럼이라 행위자 기준(USER/{actorId}, 시스템 동작은 USER/SYSTEM)으로
 * 기록한다 — 인증 이벤트는 actor 중심 사건이기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class InternalAuditService {

    private final AuditRecorder auditRecorder;

    public void record(CreateAuditLogRequest request) {
        String targetId = request.actorId() != null ? request.actorId() : "SYSTEM";
        auditRecorder.record(parseUserId(request.actorId()), request.action(), "USER", targetId,
                request.detail() == null ? null : Map.of("raw", request.detail()));
    }

    private static Long parseUserId(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(actorId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
