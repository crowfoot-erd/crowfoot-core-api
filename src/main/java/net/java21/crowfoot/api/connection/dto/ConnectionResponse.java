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
        Instant createdAt,
        /** 매니지드 데이터베이스가 등록한 커넥션인지 */
        boolean managed,
        /** MCP로 온 요청의 배포·변경 반영 허용 (Section 2.1) — 매니지드는 이 값과 무관하게 허용이다 */
        boolean mcpApplyAllowed
) {
}
