package net.java21.crowfoot.api.managed.provision;

import net.java21.crowfoot.api.connection.introspect.Introspectors;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL 프로비저닝 — 인스턴스 자격으로 접속해 발급 스키마 + LOGIN 역할(전용 계정)을
 * 만들고 그 역할에 스키마 한정 권한만 부여한다. 철회는 스키마 CASCADE DROP으로 소유
 * 객체를 정리한 뒤 역할을 DROP한다(소유 객체가 남으면 역할 DROP이 거부된다).
 * 접속은 Introspectors(타임아웃·URL 조립)를 재사용한다.
 */
@Component
public class PgSchemaProvisioner extends AbstractSchemaProvisioner {

    public PgSchemaProvisioner(Introspectors introspectors) {
        super(introspectors);
    }

    @Override
    public String dbmsType() {
        return "postgresql";
    }

    /** pgjdbc는 URL에 database가 필수라(libpq와 달리 생략 폴백이 없다),
     *  인스턴스에 database가 없으면 인스턴스 username과 같은 database로 채워 접속한다 */
    @Override
    protected Connection open(String host, int port, String databaseName, String username, String password)
            throws SQLException {
        String database = databaseName == null || databaseName.isBlank() ? username : databaseName;
        return super.open(host, port, database, username, password);
    }

    @Override
    protected List<String> provisionStatements(String schemaName, String issuedUsername,
                                                String issuedPassword) {
        String schema = quote(guarded(schemaName));
        String account = quote(guarded(issuedUsername));
        return List.of(
                "CREATE SCHEMA " + schema,
                "CREATE ROLE " + account + " LOGIN PASSWORD '"
                        + IssuedPasswords.escapeLiteral(issuedPassword) + "'",
                "GRANT USAGE, CREATE ON SCHEMA " + schema + " TO " + account,
                // 루트가 스키마에 미리 넣어둔 객체가 있다면 발급 계정도 쓸 수 있게 —
                // 앞으로 발급 계정이 만드는 객체는 자기 소유라 별도 권한이 필요 없다
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + schema + " TO " + account,
                "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA " + schema + " TO " + account);
    }

    @Override
    protected List<String> withdrawStatements(String schemaName) {
        return List.of("DROP SCHEMA IF EXISTS " + quote(guarded(schemaName)) + " CASCADE");
    }

    @Override
    protected List<String> dropAccountStatements(String issuedUsername) {
        return List.of("DROP ROLE IF EXISTS " + quote(guarded(issuedUsername)));
    }

    @Override
    protected String quote(String identifier) {
        return '"' + identifier + '"';
    }
}
