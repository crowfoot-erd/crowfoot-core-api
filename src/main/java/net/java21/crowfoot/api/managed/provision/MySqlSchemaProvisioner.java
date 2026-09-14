package net.java21.crowfoot.api.managed.provision;

import net.java21.crowfoot.api.connection.introspect.Introspectors;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MySQL 프로비저닝 — database = schema라 CREATE SCHEMA가 곧 발급 database 통째로 생성이고,
 * 전용 계정(CREATE USER '%' 호스트 — 외부 클라이언트 접속 전제)을 만들어 그 database 한정
 * 권한만 부여한다(DML + DDL 배포에 필요한 최소 세트, GRANT OPTION 제외).
 * 철회는 database DROP 후 계정 DROP(MySQL엔 CASCADE 절이 없다 — 기본 동작이 전체 삭제).
 * 발급 커넥션은 database=발급 이름·스키마 칸은 비운다.
 */
@Component
public class MySqlSchemaProvisioner extends AbstractSchemaProvisioner {

    public MySqlSchemaProvisioner(Introspectors introspectors) {
        super(introspectors);
    }

    @Override
    public String dbmsType() {
        return "mysql";
    }

    @Override
    public String connectionDatabaseName(String instanceDatabaseName, String instanceUsername, String issuedName) {
        return issuedName;
    }

    @Override
    public String connectionSchemaName(String issuedName) {
        return null;
    }

    @Override
    protected List<String> provisionStatements(String schemaName, String issuedUsername,
                                                String issuedPassword) {
        String schema = quote(guarded(schemaName));
        // user@host는 문자열 리터럴이라 홑따옴표로 감싼다 — quote()(백틱)를 쓰면 계정명 자체에
        // 백틱이 박혀 만들어진 계정과 접속 계정이 어긋난다. 이름은 guarded()가 문자 집합을 보증한다
        String user = "'" + guarded(issuedUsername) + "'@'%'";
        return List.of(
                "CREATE SCHEMA " + schema,
                "CREATE USER " + user + " IDENTIFIED BY '" + IssuedPasswords.escapeLiteral(issuedPassword) + "'",
                "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, INDEX, REFERENCES, "
                        + "CREATE VIEW, SHOW VIEW ON " + schema + ".* TO " + user);
    }

    @Override
    protected List<String> withdrawStatements(String schemaName) {
        return List.of("DROP SCHEMA IF EXISTS " + quote(guarded(schemaName)));
    }

    @Override
    protected List<String> dropAccountStatements(String issuedUsername) {
        return List.of("DROP USER IF EXISTS '" + guarded(issuedUsername) + "'@'%'");
    }

    @Override
    protected String quote(String identifier) {
        return '`' + identifier + '`';
    }
}
