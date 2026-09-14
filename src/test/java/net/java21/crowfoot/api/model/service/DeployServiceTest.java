package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.PostgresIntrospector;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.dto.ModelDeployResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.anyMap;
import static org.mockito.BDDMockito.anyString;
import static org.mockito.BDDMockito.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;

/**
 * 포워드 엔지니어링 배포 (08-core/02-model.md Section 1.8) — 문장별 실행·부분 실패 리포트·방언 일치 검사.
 * JDBC는 Introspectors를 mock으로 대체(실물 검증은 curl).
 */
@ExtendWith(MockitoExtension.class)
class DeployServiceTest {

    private static final String CONTENT = """
            {"schemaVersion":1,"model":{
              "tables":[
                {"id":"t1","physicalName":"member","columns":[
                   {"id":"c1","physicalName":"id","dataType":"BIGINT","nullable":false,"autoIncrement":true},
                   {"id":"c2","physicalName":"email","dataType":"VARCHAR","length":255,"nullable":false}],
                 "primaryKey":{"name":"pk_member","columnIds":["c1"]},"uniques":[],"indexes":[]},
                {"id":"t2","physicalName":"orders","columns":[
                   {"id":"c3","physicalName":"id","dataType":"BIGINT","nullable":false,"autoIncrement":true},
                   {"id":"c4","physicalName":"member_id","dataType":"BIGINT","nullable":false}],
                 "primaryKey":{"name":"pk_orders","columnIds":["c3"]},"uniques":[],"indexes":[]}
              ],
              "relationships":[
                {"id":"r1","fkName":"fk_orders_member","parentTableId":"t1","childTableId":"t2",
                 "identifying":false,"columnMappings":[{"parentColumnId":"c1","childColumnId":"c4"}]}
              ]}}
            """;

    @Mock
    private ModelRepository modelRepository;
    @Mock
    private DbConnectionRepository connectionRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private Introspectors introspectors;

    private DeployService deployService;

    @BeforeEach
    void setUp() {
        deployService = new DeployService(modelRepository, connectionRepository, roleChecker,
                auditRecorder, new ObjectMapper(), new ConnectionCrypto(TestKeys.DEV_KEY), introspectors);
    }

    static final class TestKeys {
        static final String DEV_KEY = java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    }

    private Model model(String databaseType) {
        Model model = new Model(77L, "주문 서비스 ERD", null, databaseType, CONTENT, 7L);
        model.setId(501L);
        return model;
    }

    private static DbConnection connection(String dbmsType) {
        DbConnection connection = new DbConnection(77L, "개발 PG", dbmsType, "s3.java21.net", 8000,
                "crowfoot", null, "crowfoot", new ConnectionCrypto(TestKeys.DEV_KEY).encrypt("crowfoot123!"), 2L);
        ReflectionTestUtils.setField(connection, "id", 9L);
        return connection;
    }

    private Statement stubJdbc() throws SQLException {
        SchemaIntrospector introspector = new PostgresIntrospector();
        given(introspectors.forDbmsType("postgresql")).willReturn(introspector);
        Connection jdbc = org.mockito.Mockito.mock(Connection.class);
        Statement statement = org.mockito.Mockito.mock(Statement.class);
        given(introspectors.open(eq(introspector), eq("s3.java21.net"), eq(8000), eq("crowfoot"),
                eq("crowfoot"), anyString())).willReturn(jdbc);
        given(jdbc.createStatement()).willReturn(statement);
        return statement;
    }

    @Test
    @DisplayName("배포 성공 — 문장(CREATE 2·ALTER 1)별 실행 결과와 감사를 기록한다")
    void deployExecutesStatements() throws SQLException {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("postgresql")));
        given(connectionRepository.findByIdAndWorkspaceId(9L, 77L))
                .willReturn(Optional.of(connection("postgresql")));
        stubJdbc();

        ModelDeployResponse response = deployService.deploy(2L, 77L, 501L, 9L);

        assertThat(response.executedCount()).isEqualTo(3);
        assertThat(response.failedCount()).isZero();
        assertThat(response.statements()).hasSize(3);
        assertThat(response.statements()).allSatisfy(statement -> {
            assertThat(statement.ok()).isTrue();
            assertThat(statement.error()).isNull();
        });
        // 문장 순서 — CREATE 먼저, FK는 마지막 ALTER(의존 순서 문제 없음)
        assertThat(response.statements().get(0).sql()).startsWith("CREATE TABLE member");
        assertThat(response.statements().get(1).sql()).startsWith("CREATE TABLE orders");
        assertThat(response.statements().get(2).sql()).startsWith("ALTER TABLE orders");
        verify(auditRecorder).record(eq(2L), eq("MODEL_DEPLOYED"), eq("MODEL"), eq("501"), anyMap());
    }

    @Test
    @DisplayName("부분 실패 — 한 문장이 실패해도 나머지를 실행해 전체 결과를 보고한다")
    void deployReportsPartialFailure() throws SQLException {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("postgresql")));
        given(connectionRepository.findByIdAndWorkspaceId(9L, 77L))
                .willReturn(Optional.of(connection("postgresql")));
        Statement statement = stubJdbc();
        given(statement.execute(anyString())).willAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("CREATE TABLE orders")) {
                throw new SQLException("relation \"orders\" already exists");
            }
            return true;
        });

        ModelDeployResponse response = deployService.deploy(2L, 77L, 501L, 9L);

        assertThat(response.executedCount()).isEqualTo(2);
        assertThat(response.failedCount()).isEqualTo(1);
        ModelDeployResponse.Statement failed = response.statements().get(1);
        assertThat(failed.ok()).isFalse();
        // 문장 오류는 서버 원문을 그대로(접속 계열이 아니면 원문이 더 실용적)
        assertThat(failed.error()).contains("already exists");
    }

    @Test
    @DisplayName("문서와 커넥션의 DBMS가 다르면 배포하지 않는다")
    void deployRejectsDbmsMismatch() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("mysql")));
        given(connectionRepository.findByIdAndWorkspaceId(9L, 77L))
                .willReturn(Optional.of(connection("postgresql")));

        assertThatThrownBy(() -> deployService.deploy(2L, 77L, 501L, 9L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("커넥션이 없으면 404 — 존재 은닉(비멤버도 같은 코드)")
    void deployRejectsMissingConnection() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("postgresql")));
        given(connectionRepository.findByIdAndWorkspaceId(9L, 77L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> deployService.deploy(2L, 77L, 501L, 9L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_FOUND));
    }

    @Test
    @DisplayName("접속 실패 — CONNECTION_UNREACHABLE로 분류해 보고한다")
    void deployReportsUnreachable() throws SQLException {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("postgresql")));
        given(connectionRepository.findByIdAndWorkspaceId(9L, 77L))
                .willReturn(Optional.of(connection("postgresql")));
        SchemaIntrospector introspector = new PostgresIntrospector();
        given(introspectors.forDbmsType("postgresql")).willReturn(introspector);
        given(introspectors.open(eq(introspector), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), anyString(), anyString()))
                .willThrow(new SQLException("Connection refused"));

        assertThatThrownBy(() -> deployService.deploy(2L, 77L, 501L, 9L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_UNREACHABLE));
    }
}
