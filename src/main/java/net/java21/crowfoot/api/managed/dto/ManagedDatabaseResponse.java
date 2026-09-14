package net.java21.crowfoot.api.managed.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/**
 * 매니지드 발급 응답 (08-core/07-managed-database.md Section 3.5) —
 * 발급 스키마와 자동 생성 커넥션의 대응 관계를 담는다. 자격 증명은 노출하지 않는다.
 */
public record ManagedDatabaseResponse(
        String databaseId,
        String instanceId,
        String instanceDisplayName,
        String schemaName,
        String workspaceId,
        String connectionId,
        String connectionName,
        UserRefResponse createdBy,
        Instant createdAt
) {
}
