package net.java21.crowfoot.api.managed.dto;

/**
 * 매니지드 발급 접속 정보 응답 (08-core/07-managed-database.md Section 3.7) —
 * 발급 소유자가 외부 클라이언트(DBeaver 등)로 접속할 수 있게 하는 유일한 비밀번호 노출 경로.
 *
 * <p>발급 커넥션과 같은 매핑(PostgreSQL: database=인스턴스 것·schema=발급 스키마 /
 * MySQL: database=발급 이름·schema=null)으로, 프로비저너 전략에서 다시 계산한다 —
 * 커넥션 이름 변경과 무관하게 항상 발급 시점의 접속 정보를 보여준다.
 * 조회 시마다 복호화해 내려가며 감사(MANAGED_DATABASE_CREDENTIAL_VIEWED)를 남긴다.
 */
public record ManagedCredentialResponse(
        String databaseId,
        String instanceDisplayName,
        String dbmsType,
        String host,
        int port,
        String databaseName,
        String schemaName,
        String username,
        String password
) {
}
