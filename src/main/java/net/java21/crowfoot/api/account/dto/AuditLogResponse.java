package net.java21.crowfoot.api.account.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 관리자 감사 로그 응답 (08-core/05-account.md Section 2.7).
 * actorUserId가 null이면 시스템 행(만료 정리 등) — actorName·actorEmail도 null.
 * detail은 저장된 JSON을 파싱한 객체(파싱 실패·미기록은 null).
 */
public record AuditLogResponse(
        String id,
        Instant createdAt,
        Long actorUserId,
        String actorName,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        Map<String, Object> detail,
        String ip
) {

    public AuditLogResponse(Long id, Instant createdAt, Long actorUserId, String actorName, String actorEmail,
                            String action, String targetType, String targetId, Map<String, Object> detail, String ip) {
        this(Long.toString(id), createdAt, actorUserId, actorName, actorEmail, action, targetType, targetId, detail, ip);
    }
}
