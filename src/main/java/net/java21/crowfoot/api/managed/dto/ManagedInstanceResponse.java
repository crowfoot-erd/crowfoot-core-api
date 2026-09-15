package net.java21.crowfoot.api.managed.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/**
 * 매니지드 인스턴스 응답 (08-core/07-managed-database.md Section 3.4) —
 * 자격 증명 중 username까지는 관리자 화면 표기용으로 노출하되 password는 어떤 형태로도 내보내지 않는다.
 */
public record ManagedInstanceResponse(
        String instanceId,
        String displayName,
        String dbmsType,
        String host,
        String publicHost,
        int port,
        String databaseName,
        String username,
        boolean isActive,
        long issuedCount,
        UserRefResponse createdBy,
        Instant createdAt
) {
}
