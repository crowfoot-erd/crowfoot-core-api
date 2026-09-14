package net.java21.crowfoot.api.connection.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/**
 * 커넥션 응답 (08-core/06-connection.md Section 3) — 비밀번호는 어떤 형태로도 포함하지 않는다.
 */
public record ConnectionResponse(
        String connectionId,
        String workspaceId,
        String name,
        String dbmsType,
        String host,
        int port,
        String databaseName,
        /** PostgreSQL 스키마 — 미지정이면 null */
        String schemaName,
        String username,
        UserRefResponse createdBy,
        Instant createdAt
) {
}
