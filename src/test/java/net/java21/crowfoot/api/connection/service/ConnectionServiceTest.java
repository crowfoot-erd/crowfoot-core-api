package net.java21.crowfoot.api.connection.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.dto.ConnectionResponse;
import net.java21.crowfoot.api.connection.dto.ConnectionTestResponse;
import net.java21.crowfoot.api.connection.dto.CreateConnectionRequest;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.PostgresIntrospector;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 커넥션 관리 API 단위 테스트 (08-core/06-connection.md Section 3) —
 * 등록(비활성 dbms 400·비밀번호 암호화 저장)·변경(비밀번호 재암호화)·삭제·접속 테스트(성공/실패 응답).
 */
@ExtendWith(MockitoExtension.class)
class ConnectionServiceTest {

    @Mock
    private DbConnectionRepository connectionRepository;
    @Mock
    private DatabaseTypeRepository databaseTypeRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private Introspectors introspectors;
    @Mock
    private net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository managedDatabaseRepository;

    private ConnectionService connectionService;

    @BeforeEach
    void setUp() {
        connectionService = new ConnectionService(connectionRepository, databaseTypeRepository, userRepository,
                roleChecker, auditRecorder, new ConnectionCrypto(TestKeys.DEV_KEY), introspectors,
                managedDatabaseRepository);
    }

    static final class TestKeys {
        static final String DEV_KEY = java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    }

    private static DbConnection saved(long id) {
        DbConnection connection = new DbConnection(7L, "개발 PG", "postgresql", "s3.java21.net", 8000,
                "crowfoot", null, "crowfoot",
                new ConnectionCrypto(TestKeys.DEV_KEY).encrypt("crowfoot123!"), 2L);
        ReflectionTestUtils.setField(connection, "id", id);
        ReflectionTestUtils.setField(connection, "createdAt", Instant.parse("2026-09-14T01:00:00Z"));
        return connection;
    }

    @Test
    @DisplayName("등록 — 비활성 dbmsType은 INVALID_DBMS_TYPE로 거부한다")
    void createInactiveDbms() {
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("oracle")).willReturn(Optional.empty());

        assertThatThrownBy(() -> connectionService.create(2L, 7L,
                new CreateConnectionRequest("x", "oracle", "h", 1521, "orcl", null, "u", "p")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_DBMS_TYPE);
    }

    @Test
    @DisplayName("등록 — 비밀번호는 암호문으로 저장되고 응답에 나가지 않는다")
    void createEncryptsPassword() {
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new DatabaseType()));
        given(connectionRepository.save(any())).willAnswer(inv -> {
            DbConnection stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 99L);
            return stored;
        });

        ConnectionResponse response = connectionService.create(2L, 7L,
                new CreateConnectionRequest("개발 PG", "postgresql", "s3.java21.net", 8000,
                        "crowfoot", "crowfoot_sample", "crowfoot", "crowfoot123!"));

        ArgumentCaptor<DbConnection> captor = ArgumentCaptor.forClass(DbConnection.class);
        verify(connectionRepository).save(captor.capture());
        DbConnection stored = captor.getValue();
        // 암호문은 평문이 아니고 복호화하면 원문이 나온다
        assertThat(stored.getPassword()).isNotEqualTo("crowfoot123!".getBytes());
        assertThat(new ConnectionCrypto(TestKeys.DEV_KEY).decrypt(stored.getPassword()))
                .isEqualTo("crowfoot123!");
        assertThat(response.username()).isEqualTo("crowfoot");
        assertThat(response.connectionId()).isEqualTo("99");
        // 스키마 지정은 PostgreSQL 커넥션에 그대로 저장돼 응답에도 나간다
        assertThat(stored.getSchemaName()).isEqualTo("crowfoot_sample");
        assertThat(response.schemaName()).isEqualTo("crowfoot_sample");
        verify(auditRecorder).record(eq(2L), eq("CONNECTION_CREATED"), eq("CONNECTION"), any(), any());
    }

    @Test
    @DisplayName("등록 — PostgreSQL이 아닌 커넥션에 schemaName이 오면 거부한다")
    void createRejectsSchemaOnNonPostgres() {
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("mysql"))
                .willReturn(Optional.of(new DatabaseType()));

        assertThatThrownBy(() -> connectionService.create(2L, 7L,
                new CreateConnectionRequest("개발 MySQL", "mysql", "db.dev", 3306,
                        "orders", "crowfoot_sample", "crowfoot", "pw")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("등록 — 스키마 이름은 식별자 패턴(영문·숫자·밑줄)만 허용한다")
    void createRejectsInvalidSchemaName() {
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new DatabaseType()));

        assertThatThrownBy(() -> connectionService.create(2L, 7L,
                new CreateConnectionRequest("개발 PG", "postgresql", "s3.java21.net", 8000,
                        "crowfoot", "bad schema; --", "crowfoot", "pw")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("변경 — schemaName 빈 문자열은 해제(null), dbmsType 전환 시 스키마를 정리한다")
    void patchSchemaNameLifecycle() throws SQLException {
        DbConnection connection = saved(1L);
        connection.setSchemaName("crowfoot_sample");
        given(connectionRepository.findByIdAndWorkspaceId(1L, 7L)).willReturn(Optional.of(connection));
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("mysql")).willReturn(Optional.of(new DatabaseType()));

        connectionService.patch(2L, 7L, 1L,
                new ObjectMapper().readTree("{\"schemaName\": \"  \"}"));
        assertThat(connection.getSchemaName()).isNull();

        connection.setSchemaName("crowfoot_sample");
        connectionService.patch(2L, 7L, 1L,
                new ObjectMapper().readTree("{\"schemaName\": \"analytics\"}"));
        assertThat(connection.getSchemaName()).isEqualTo("analytics");

        // PostgreSQL → MySQL 전환 — 스키마 지정은 의미를 잃으니 함께 지운다
        connectionService.patch(2L, 7L, 1L,
                new ObjectMapper().readTree("{\"dbmsType\": \"mysql\"}"));
        assertThat(connection.getSchemaName()).isNull();
    }

    @Test
    @DisplayName("변경 — password가 오면 재암호화, 오지 않으면 기존 암호문을 유지한다")
    void patchPasswordOnlyWhenPresent() throws SQLException {
        DbConnection connection = saved(1L);
        byte[] original = connection.getPassword();
        given(connectionRepository.findByIdAndWorkspaceId(1L, 7L)).willReturn(Optional.of(connection));
        tools.jackson.databind.JsonNode body = new ObjectMapper().readTree(
                "{\"name\": \"운영 PG\", \"port\": 5432}");

        connectionService.patch(2L, 7L, 1L, body);

        assertThat(connection.getName()).isEqualTo("운영 PG");
        assertThat(connection.getPort()).isEqualTo(5432);
        assertThat(connection.getPassword()).isEqualTo(original);

        connectionService.patch(2L, 7L, 1L, new ObjectMapper().readTree("{\"password\": \"newpw\"}"));
        assertThat(connection.getPassword()).isNotEqualTo(original);
        assertThat(new ConnectionCrypto(TestKeys.DEV_KEY).decrypt(connection.getPassword())).isEqualTo("newpw");
    }

    @Test
    @DisplayName("삭제 — Editor 이상, 물리 삭제 + 감사")
    void delete() {
        DbConnection connection = saved(1L);
        given(connectionRepository.findByIdAndWorkspaceId(1L, 7L)).willReturn(Optional.of(connection));

        connectionService.delete(2L, 7L, 1L);

        verify(connectionRepository).deleteById(1L);
        verify(auditRecorder).record(eq(2L), eq("CONNECTION_DELETED"), eq("CONNECTION"), eq("1"), any());
    }

    @Test
    @DisplayName("접속 테스트 — SELECT 1 성공 시 connected:true와 지연을 보고한다")
    void testSuccess() throws SQLException {
        DbConnection connection = saved(1L);
        given(connectionRepository.findByIdAndWorkspaceId(1L, 7L)).willReturn(Optional.of(connection));
        SchemaIntrospector introspector = new PostgresIntrospector();
        given(introspectors.forDbmsType("postgresql")).willReturn(introspector);

        Connection jdbc = mock(Connection.class);
        Statement statement = mock(Statement.class);
        given(introspectors.open(eq(introspector), eq("s3.java21.net"), eq(8000), eq("crowfoot"),
                eq("crowfoot"), any())).willReturn(jdbc);
        given(jdbc.createStatement()).willReturn(statement);
        given(statement.execute("SELECT 1")).willReturn(true);

        ConnectionTestResponse response = connectionService.test(2L, 7L, 1L);

        assertThat(response.connected()).isTrue();
        assertThat(response.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("접속 테스트 — 실패도 200 응답 계약으로: connected:false와 분류 문구")
    void testFailureReportsContract() throws SQLException {
        DbConnection connection = saved(1L);
        given(connectionRepository.findByIdAndWorkspaceId(1L, 7L)).willReturn(Optional.of(connection));
        SchemaIntrospector introspector = new PostgresIntrospector();
        given(introspectors.forDbmsType("postgresql")).willReturn(introspector);
        given(introspectors.open(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any()))
                .willThrow(new SQLException("FATAL: password authentication failed", "28P01"));

        ConnectionTestResponse response = connectionService.test(2L, 7L, 1L);

        assertThat(response.connected()).isFalse();
        assertThat(response.message()).contains("인증");
    }

    @Test
    @DisplayName("접속 테스트 — 스키마 지정 커넥션은 존재 검증·search_path 설정까지 통과해야 연결된다")
    void testValidatesSchema() throws SQLException {
        DbConnection connection = saved(1L);
        connection.setSchemaName("crowfoot_sample");
        given(connectionRepository.findByIdAndWorkspaceId(1L, 7L)).willReturn(Optional.of(connection));
        SchemaIntrospector introspector = new PostgresIntrospector();
        given(introspectors.forDbmsType("postgresql")).willReturn(introspector);

        Connection jdbc = mock(Connection.class);
        Statement statement = mock(Statement.class);
        java.sql.PreparedStatement probe = mock(java.sql.PreparedStatement.class);
        java.sql.ResultSet probeResult = mock(java.sql.ResultSet.class);
        given(introspectors.open(eq(introspector), eq("s3.java21.net"), eq(8000), eq("crowfoot"),
                eq("crowfoot"), any())).willReturn(jdbc);
        given(jdbc.createStatement()).willReturn(statement);
        given(statement.execute("SELECT 1")).willReturn(true);
        // 존재 검증(pg_namespace)과 set_config(search_path) — prepareStatement 경로
        given(jdbc.prepareStatement(any())).willReturn(probe);
        given(probe.executeQuery()).willReturn(probeResult);
        given(probeResult.next()).willReturn(true);
        given(probeResult.getInt(1)).willReturn(1);

        ConnectionTestResponse response = connectionService.test(2L, 7L, 1L);

        assertThat(response.connected()).isTrue();
        org.mockito.Mockito.verify(probe, org.mockito.Mockito.times(2)).setString(1, "crowfoot_sample");
    }

    @Test
    @DisplayName("접속 테스트 — 없는 스키마면 connected:false와 원문 안내로 떨어뜨린다")
    void testReportsMissingSchema() throws SQLException {
        DbConnection connection = saved(1L);
        connection.setSchemaName("ghost_schema");
        given(connectionRepository.findByIdAndWorkspaceId(1L, 7L)).willReturn(Optional.of(connection));
        SchemaIntrospector introspector = new PostgresIntrospector();
        given(introspectors.forDbmsType("postgresql")).willReturn(introspector);

        Connection jdbc = mock(Connection.class);
        java.sql.PreparedStatement probe = mock(java.sql.PreparedStatement.class);
        java.sql.ResultSet probeResult = mock(java.sql.ResultSet.class);
        given(introspectors.open(eq(introspector), eq("s3.java21.net"), eq(8000), eq("crowfoot"),
                eq("crowfoot"), any())).willReturn(jdbc);
        given(jdbc.prepareStatement(any())).willReturn(probe);
        given(probe.executeQuery()).willReturn(probeResult);
        given(probeResult.next()).willReturn(true);
        given(probeResult.getInt(1)).willReturn(0); // pg_namespace에 없다

        ConnectionTestResponse response = connectionService.test(2L, 7L, 1L);

        assertThat(response.connected()).isFalse();
        assertThat(response.message()).contains("ghost_schema");
    }

    @Test
    @DisplayName("권한 — 목록은 멤버 게이트(requireMember)를 지난다")
    void roleGates() {
        given(roleChecker.requireMember(anyLong(), anyLong())).willReturn(null);
        given(connectionRepository.findByWorkspaceIdOrderByCreatedAtAscIdAsc(7L)).willReturn(java.util.List.of());

        connectionService.list(2L, 7L);
        verify(roleChecker).requireMember(2L, 7L);
    }

    @Test
    @DisplayName("매니지드 발급 커넥션 — 이름은 바꿀 수 있지만 접속 정보 변경은 거부한다")
    void patchManagedConnectionGuardsCredential() throws SQLException {
        DbConnection connection = saved(1L);
        given(connectionRepository.findByIdAndWorkspaceId(1L, 7L)).willReturn(Optional.of(connection));
        given(managedDatabaseRepository.findByConnectionId(1L)).willReturn(Optional.of(
                new net.java21.crowfoot.api.managed.domain.ManagedDatabase(1L, 2L, 7L, "cf_u2_d1", 1L)));

        // name 단독 변경은 허용
        connectionService.patch(2L, 7L, 1L, new ObjectMapper().readTree("{\"name\": \"내 발급 DB\"}"));
        assertThat(connection.getName()).isEqualTo("내 발급 DB");

        // 접속 정보 키가 하나라도 오면 거부
        assertThatThrownBy(() -> connectionService.patch(2L, 7L, 1L,
                new ObjectMapper().readTree("{\"schemaName\": \"other_u9_d1\"}")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("매니지드 발급 커넥션 — 커넥션 API 삭제는 거부한다(정리는 철회로)")
    void deleteManagedConnectionRejected() {
        DbConnection connection = saved(1L);
        given(connectionRepository.findByIdAndWorkspaceId(1L, 7L)).willReturn(Optional.of(connection));
        given(managedDatabaseRepository.findByConnectionId(1L)).willReturn(Optional.of(
                new net.java21.crowfoot.api.managed.domain.ManagedDatabase(1L, 2L, 7L, "cf_u2_d1", 1L)));

        assertThatThrownBy(() -> connectionService.delete(2L, 7L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(connectionRepository, org.mockito.Mockito.never()).deleteById(anyLong());
    }
}
