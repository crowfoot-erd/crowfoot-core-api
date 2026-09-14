package net.java21.crowfoot.api.managed.provision;

import net.java21.crowfoot.api.connection.dto.ConnectionTestResponse;

/**
 * 매니지드 발급 프로비저닝 전략 (08-core/07-managed-database.md Section 1) —
 * 발급 = 스키마 + 그 스키마만 쓸 수 있는 **전용 DB 계정**(계정명 = 스키마명, 자격은 서버 생성).
 * 인스턴스 루트 자격은 프로비저닝에만 쓰고 사용자에게는 절대 내주지 않는다.
 * MySQL은 database = schema라 CREATE SCHEMA가 곧 발급 database 생성이고,
 * 서비스는 전략에만 의존한다 — 신규 DBMS는 이 인터페이스의 새 구현으로 추가한다.
 *
 * <p>database 지정은 두 DBMS 모두 생략 가능하다 — PostgreSQL은 JDBC 표준 폴백으로
 * 인스턴스 username과 같은 database에 접속하고(존재 보장), MySQL은 발급 시 database를 새로 만든다.
 * 잘못된 조합은 등록 검증(SELECT 1)에서 즉시 발각된다.
 *
 * <p>실패 분류: 접속 수립 실패는 {@code MANAGED_INSTANCE_UNREACHABLE},
 * 문장 실행 실패(CREATE/DROP·계정·권한)는 {@code MANAGED_PROVISION_FAILED}로 던진다.
 */
public interface ManagedProvisioner {

    String dbmsType();

    /**
     * 발급 커넥션의 database 값 — PostgreSQL은 인스턴스 database 그대로(없으면 인스턴스
     * username database — 존재가 보장되는 이름), MySQL은 발급 이름이 곧 database다.
     * 접속 계정은 발급 계정이지만 database 폴백 이름은 인스턴스 것을 따른다.
     */
    default String connectionDatabaseName(String instanceDatabaseName, String instanceUsername, String issuedName) {
        return instanceDatabaseName != null ? instanceDatabaseName : instanceUsername;
    }

    /** 발급 커넥션의 스키마 값 — MySQL은 database = schema라 스키마 칸을 비운다(null) */
    default String connectionSchemaName(String issuedName) {
        return issuedName;
    }

    /** 등록 검증 — 제출된 루트 자격으로 SELECT 1 (실패는 예외로 던진다) */
    void verify(String host, int port, String databaseName, String username, String password);

    /** 접속 테스트 — verify와 같은 SELECT 1이되 실패를 예외가 아니라 계약 응답
     *  (connected=false + 분류 문구)으로 돌려준다. 저장된 인스턴스의 상시 점검용 */
    ConnectionTestResponse test(
            String host, int port, String databaseName, String username, String password);

    /**
     * 발급 — 스키마 생성 + 전용 계정 생성·스키마 한정 권한 부여.
     * 계정명·비밀번호는 서버가 생성해 전달한다(사용자 입력이 조립에 오지 않는다).
     * 이미 있으면 PROVISION_FAILED(이름 충돌 진단).
     */
    void provision(String host, int port, String databaseName, String username, String password,
                   String schemaName, String issuedUsername, String issuedPassword);

    /**
     * 철회 — 스키마 DROP + 전용 계정 DROP. 없으면 이미 정리된 것으로 치고 성공(관대한 회수).
     * issuedUsername이 null이면 계정 없는 구방식 이력이라 스키마만 회수한다.
     */
    void withdraw(String host, int port, String databaseName, String username, String password,
                  String schemaName, String issuedUsername);
}
