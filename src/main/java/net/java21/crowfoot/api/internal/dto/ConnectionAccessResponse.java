package net.java21.crowfoot.api.internal.dto;

import net.java21.crowfoot.api.connection.domain.DbConnection;

/**
 * 접근 확인 응답 — 권한 판정을 통과한 커넥션의 접속 정보 (08-core/15-internal-api.md Section 2.1).
 *
 * <p>{@code password}는 복호화한 평문이다. 이 값이 core 밖으로 나가는 경로는 이 응답과
 * 매니지드 데이터베이스 접속 정보 조회(07-managed-database.md Section 3.7)뿐이다.
 * 로그에 남기지 않는다 — record의 기본 toString이 값을 찍지 않도록 {@link #toString()}을 가린다.
 */
public record ConnectionAccessResponse(
        String connectionId,
        String connectionName,
        String workspaceId,
        String role,
        String dbmsType,
        String host,
        int port,
        String databaseName,
        String schemaName,
        String username,
        String password
) {

    /** @param host 서버가 접속할 때 쓰는 주소 — 매니지드 커넥션은 내부 주소다(07-managed-database.md Section 3.9) */
    public static ConnectionAccessResponse of(DbConnection connection, String role, String plainPassword, String host, int port) {
        return new ConnectionAccessResponse(
                Long.toString(connection.getId()),
                connection.getName(),
                Long.toString(connection.getWorkspaceId()),
                role,
                connection.getDbmsType(),
                host,
                port,
                connection.getDatabaseName(),
                connection.getSchemaName(),
                connection.getUsername(),
                plainPassword);
    }

    @Override
    public String toString() {
        return "ConnectionAccessResponse[connectionId=" + connectionId + ", workspaceId=" + workspaceId
                + ", role=" + role + ", dbmsType=" + dbmsType + ", host=" + host + ", port=" + port
                + ", databaseName=" + databaseName + ", schemaName=" + schemaName
                + ", username=" + username + ", password=***]";
    }
}
