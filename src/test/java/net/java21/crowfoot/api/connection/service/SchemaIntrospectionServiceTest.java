package net.java21.crowfoot.api.connection.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.dto.ConnectionSchemaResponse;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.MySqlIntrospector;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 스키마 조회 단위 테스트 (08-core/06-connection.md Section 3.7) — 동기화 원천 content 반환·
 * 커넥션 없음 404·dbms 미지원 400·접속 실패 502. 문서를 만들지 않는다는 절반만 담당한다.
 * introspection은 실물 전략을 모킹된 JDBC Connection 위에 얹지 않고 스텁으로 대체한다(리버스 테스트 관례).
 */
@ExtendWith(MockitoExtension.class)
class SchemaIntrospectionServiceTest {

    private static final String DEV_KEY = java.util.Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Mock
    private DbConnectionRepository connectionRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private Introspectors introspectors;

    private SchemaIntrospectionService service;

    /** commonTypeCode·jdbcUrl만 실물처럼 동작하는 스텁 전략 — introspect 결과는 고정 */
    private final SchemaIntrospector stubIntrospector = new MySqlIntrospector() {
        @Override
        public IntrospectedSchema introspect(Connection connection, String schemaName) {
            return new IntrospectedSchema(List.of(
                    new IntrospectedSchema.IntrospectedTable(
                            "orders", null,
                            List.of(new IntrospectedSchema.IntrospectedColumn(
                                    "id", "bigint", null, null, null, false, null, true, null)),
                            "PRIMARY", List.of("id"), List.of())),
                    List.of());
        }
    };

    @BeforeEach
    void setUp() {
        service = new SchemaIntrospectionService(connectionRepository, roleChecker, auditRecorder,
                new ConnectionCrypto(DEV_KEY), introspectors, new ReverseContentAssembler());
    }

    private DbConnection connection() {
        DbConnection connection = new DbConnection(7L, "개발 MySQL", "mysql", "db.dev", 3306,
                "orders", null, "crowfoot", new ConnectionCrypto(DEV_KEY).encrypt("pw"), 2L);
        ReflectionTestUtils.setField(connection, "id", 11L);
        return connection;
    }

    private void stubJdbc() throws SQLException {
        given(introspectors.forDbmsType("mysql")).willReturn(stubIntrospector);
        // 접속은 실제로 열지 않는다 — introspect가 Connection을 쓰지 않는 스텁이므로 null을 돌려도 안전
        given(introspectors.open(any(), any(), anyInt(), any(), any(), any())).willReturn(null);
    }

    @Test
    @DisplayName("조회 — Canonical content v1과 요약을 반환하고 감사를 남긴다 (문서 저장 없음)")
    void introspectReturnsContent() throws SQLException {
        given(connectionRepository.findByIdAndWorkspaceId(11L, 7L)).willReturn(Optional.of(connection()));
        stubJdbc();

        ConnectionSchemaResponse response = service.introspect(2L, 7L, 11L);

        assertThat(response.content()).contains("\"schemaVersion\":1");
        assertThat(response.content()).contains("\"physicalName\":\"orders\"");
        assertThat(response.tableCount()).isEqualTo(1);
        assertThat(response.relationshipCount()).isZero();
        assertThat(response.skipped()).isEmpty();
        verify(auditRecorder).record(eq(2L), eq("CONNECTION_SCHEMA_INTROSPECTED"), eq("CONNECTION"),
                eq("11"), any());
    }

    @Test
    @DisplayName("조회 — 커넥션이 없으면 404 CONNECTION_NOT_FOUND")
    void introspectUnknownConnection() {
        given(connectionRepository.findByIdAndWorkspaceId(11L, 7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.introspect(2L, 7L, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONNECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("조회 — introspector가 없는 dbms는 400 INVALID_DBMS_TYPE")
    void introspectUnsupportedDbms() {
        given(connectionRepository.findByIdAndWorkspaceId(11L, 7L)).willReturn(Optional.of(connection()));
        given(introspectors.forDbmsType("mysql")).willReturn(null);

        assertThatThrownBy(() -> service.introspect(2L, 7L, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_DBMS_TYPE);
    }

    @Test
    @DisplayName("조회 — 접속 실패는 502 CONNECTION_UNREACHABLE과 분류 문구")
    void introspectUnreachable() throws SQLException {
        given(connectionRepository.findByIdAndWorkspaceId(11L, 7L)).willReturn(Optional.of(connection()));
        given(introspectors.forDbmsType("mysql")).willReturn(stubIntrospector);
        given(introspectors.open(any(), any(), anyInt(), any(), any(), any()))
                .willThrow(new SQLException("Connection refused", "08001"));

        assertThatThrownBy(() -> service.introspect(2L, 7L, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONNECTION_UNREACHABLE);
    }
}
