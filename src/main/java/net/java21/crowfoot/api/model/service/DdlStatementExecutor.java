package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.service.ConnectionEndpointResolver;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.JdbcDiagnostics;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.model.dto.ModelDeployResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * DDL 문장 실행기 (08-core/02-model.md §1.8 배포·§1.15 마이그레이션 실행 공용 골격).
 *
 * <p>커넥션 대상 데이터베이스에 문장을 순서대로 실행한다. 한 문장이 실패해도 나머지를
 * 계속 실행해 문장별 성공/실패를 돌려준다(부분 실패 리포트). JDBC 자동 커밋이라 스프링
 * 트랜잭션을 물지 않는다. 문장 실패는 결과 항목으로 보고하고, 접속 수준 실패(open·세션
 * 스키마)만 CONNECTION_UNREACHABLE로 던진다.
 */
@Service
@RequiredArgsConstructor
public class DdlStatementExecutor {

    private final ConnectionEndpointResolver endpoints;
    private final Introspectors introspectors;
    private final ConnectionCrypto crypto;

    /** 문장별 실행 — 호출부가 만든 문장 리스트를 커넥션 DB에서 순서대로 실행한다 */
    public List<ModelDeployResponse.Statement> execute(DbConnection connection, List<String> statements) {
        SchemaIntrospector introspector = introspectors.forDbmsType(connection.getDbmsType());
        if (introspector == null) {
            throw new BusinessException(ErrorCode.INVALID_DBMS_TYPE);
        }

        List<ModelDeployResponse.Statement> results = new ArrayList<>();
        try (Connection jdbc = introspectors.open(introspector, endpoints.resolve(connection).host(), endpoints.resolve(connection).port(),
                connection.getDatabaseName(), connection.getUsername(), crypto.decrypt(connection.getPassword()))) {
            // 스키마 지정 커넥션(PG)은 세션 search_path로 대상을 고정한다 — 없는 스키마면 여기서 실패한다
            introspector.applySessionSchema(jdbc, connection.getSchemaName());
            for (String sql : statements) {
                try (Statement statement = jdbc.createStatement()) {
                    statement.execute(sql);
                    results.add(new ModelDeployResponse.Statement(sql, true, null));
                } catch (SQLException e) {
                    results.add(new ModelDeployResponse.Statement(sql, false, JdbcDiagnostics.diagnoseStatement(e)));
                }
            }
        } catch (SQLException e) {
            throw new BusinessException(ErrorCode.CONNECTION_UNREACHABLE, JdbcDiagnostics.diagnoseStatement(e));
        }
        return results;
    }
}
