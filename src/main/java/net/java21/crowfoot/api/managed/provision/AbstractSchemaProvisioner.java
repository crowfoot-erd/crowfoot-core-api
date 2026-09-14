package net.java21.crowfoot.api.managed.provision;

import net.java21.crowfoot.api.connection.dto.ConnectionTestResponse;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.JdbcDiagnostics;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 스키마 프로비저닝 공통 골격 — 접속(Introspectors 재사용)·등록 검증(SELECT 1)·
 * 발급 이름 이중 방어(서버 생성 규칙 패턴)를 담당하고, DBMS 차이(식별자 인용·계정 문법·
 * DROP 문법·커넥션 매핑)만 하위 전략이 채운다.
 *
 * <p>provision·withdraw 문장은 순차 실행이다(중간 실패 시 이미 실행된 문장은 남는다) —
 * 서비스가 실패를 받아 withdraw 보상으로 전부 회수한다. 회수 문장은 전부 IF EXISTS라
 * 만들어지지 않은 객체도 무해하다.
 */
abstract class AbstractSchemaProvisioner implements ManagedProvisioner {

    protected final Introspectors introspectors;

    /** 서버 생성 스키마·계정명 규칙 — 영문 소문자·숫자·밑줄(첫 글자 숫자 금지) */
    private static final Pattern SERVER_NAMING = Pattern.compile("^[a-z_][a-z0-9_]{0,99}$");

    protected AbstractSchemaProvisioner(Introspectors introspectors) {
        this.introspectors = introspectors;
    }

    @Override
    public void verify(String host, int port, String databaseName, String username, String password) {
        ConnectionTestResponse result = test(host, port, databaseName, username, password);
        if (!result.connected()) {
            throw new BusinessException(ErrorCode.MANAGED_INSTANCE_UNREACHABLE, result.message());
        }
    }

    /** 접속·SELECT 1 지연을 재고 실패는 분류 문구로 돌린다 — 폴백(open 오버라이드)까지 공유한다 */
    @Override
    public ConnectionTestResponse test(String host, int port, String databaseName, String username,
                                        String password) {
        long start = System.nanoTime();
        try (Connection connection = open(host, port, databaseName, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return ConnectionTestResponse.ok((System.nanoTime() - start) / 1_000_000);
        } catch (SQLException e) {
            return ConnectionTestResponse.fail(JdbcDiagnostics.diagnoseStatement(e));
        }
    }

    @Override
    public void provision(String host, int port, String databaseName, String username, String password,
                          String schemaName, String issuedUsername, String issuedPassword) {
        execute(host, port, databaseName, username, password,
                provisionStatements(schemaName, issuedUsername, issuedPassword));
    }

    @Override
    public void withdraw(String host, int port, String databaseName, String username, String password,
                         String schemaName, String issuedUsername) {
        List<String> statements = new ArrayList<>(withdrawStatements(schemaName));
        if (issuedUsername != null) {
            statements.addAll(dropAccountStatements(issuedUsername));
        }
        execute(host, port, databaseName, username, password, statements);
    }

    /** DBMS별 발급 문장 — 스키마 생성 + 전용 계정 생성·스키마 한정 권한 */
    protected abstract List<String> provisionStatements(String schemaName, String issuedUsername,
                                                        String issuedPassword);

    /** 스키마 회수 문장 — 계정 DROP보다 먼저 실행돼 소유 객체를 정리한다 */
    protected abstract List<String> withdrawStatements(String schemaName);

    /** 전용 계정 회수 문장 — 스키마 선행 DROP으로 소유 객체가 없는 상태에서 실행된다 */
    protected abstract List<String> dropAccountStatements(String issuedUsername);

    private void execute(String host, int port, String databaseName, String username, String password,
                         List<String> statements) {
        try (Connection connection = open(host, port, databaseName, username, password);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new BusinessException(ErrorCode.MANAGED_PROVISION_FAILED,
                    JdbcDiagnostics.diagnoseStatement(e));
        }
    }

    protected Connection open(String host, int port, String databaseName, String username, String password)
            throws SQLException {
        SchemaIntrospector introspector = introspectors.forDbmsType(dbmsType());
        if (introspector == null) {
            throw new BusinessException(ErrorCode.INVALID_DBMS_TYPE);
        }
        return introspectors.open(introspector, host, port, databaseName, username, password);
    }

    /** 식별자 인용 — 하위 전략이 문법을 정한다(PG {@code "..."} / MySQL {@code `...`}) */
    protected abstract String quote(String identifier);

    /** 서버 생성 규칙 외 이름은 거부한다(조립 전 이중 검증 — 사용자 입력이 조립에 오지 않는다) */
    protected String guarded(String name) {
        if (name == null || !SERVER_NAMING.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.MANAGED_PROVISION_FAILED,
                    "발급 스키마/계정 이름이 규칙(cf_u{userId}_d{seq})에 맞지 않습니다");
        }
        return name;
    }
}
