package net.java21.crowfoot.api.connection.service;

import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.dto.ReverseEngineeringRequest;
import net.java21.crowfoot.api.connection.dto.ReverseEngineeringResponse;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.MySqlIntrospector;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.repository.ModelDiagramRepository;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.model.service.ModelVersionPruner;
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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 리버스 엔지니어링 단위 테스트 (08-core/06-connection.md Section 3.6) —
 * 모델 생성+content 저장·이름 기본값·이름 중복 409·접속 실패 502·dbms 미지원 400.
 * introspection은 실물 전략을 모킹된 JDBC Connection 위에 얹지 않고 스텁으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class ReverseEngineeringServiceTest {

    private static final String DEV_KEY = java.util.Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Mock
    private DbConnectionRepository connectionRepository;
    @Mock
    private ModelRepository modelRepository;
    @Mock
    private ModelDiagramRepository modelDiagramRepository;
    @Mock
    private ModelVersionRepository modelVersionRepository;
    @Mock
    private ModelVersionPruner modelVersionPruner;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private Introspectors introspectors;

    private ReverseEngineeringService service;

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
        service = new ReverseEngineeringService(connectionRepository, modelRepository, modelDiagramRepository,
                modelVersionRepository, modelVersionPruner, userRepository, roleChecker, auditRecorder,
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
    @DisplayName("리버스 — 문서 이름 기본값({커넥션 이름} ERD)·content 저장·main 다이어그램 생성")
    void reverseCreatesModelWithContent() throws SQLException {
        given(connectionRepository.findByIdAndWorkspaceId(11L, 7L)).willReturn(Optional.of(connection()));
        given(modelRepository.existsByWorkspaceIdAndName(7L, "개발 MySQL ERD")).willReturn(false);
        stubJdbc();
        given(modelRepository.save(any())).willAnswer(inv -> {
            Model model = inv.getArgument(0);
            ReflectionTestUtils.setField(model, "id", 501L);
            ReflectionTestUtils.setField(model, "createdAt", Instant.now());
            ReflectionTestUtils.setField(model, "updatedAt", Instant.now());
            return model;
        });

        ReverseEngineeringResponse response = service.reverse(2L, 7L, 11L, new ReverseEngineeringRequest(null, null));

        assertThat(response.model().name()).isEqualTo("개발 MySQL ERD");
        assertThat(response.model().databaseType()).isEqualTo("mysql");
        assertThat(response.tableCount()).isEqualTo(1);
        assertThat(response.skipped()).isEmpty();

        ArgumentCaptor<Model> modelCaptor = ArgumentCaptor.forClass(Model.class);
        verify(modelRepository).save(modelCaptor.capture());
        assertThat(modelCaptor.getValue().getContent()).contains("\"physicalName\":\"orders\"");
        assertThat(modelCaptor.getValue().getContent()).contains("\"schemaVersion\":1");
        // 원천 커넥션 연관 — 이 문서가 어느 연결에서 왔는지 기억한다(동기화 버튼 노출 근거)
        assertThat(modelCaptor.getValue().getSourceConnectionId()).isEqualTo(11L);
        ArgumentCaptor<ModelDiagram> diagramCaptor = ArgumentCaptor.forClass(ModelDiagram.class);
        verify(modelDiagramRepository).save(diagramCaptor.capture());
        assertThat(diagramCaptor.getValue().isMain()).isTrue();
        // v0 스냅샷 — 리버스 태생 요약은 고정형 JSON {created,tables,relationships} (02-model.md 1.11)
        ArgumentCaptor<ModelVersion> snapshotCaptor = ArgumentCaptor.forClass(ModelVersion.class);
        verify(modelVersionRepository).save(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getModelId()).isEqualTo(501L);
        assertThat(snapshotCaptor.getValue().getVersion()).isZero();
        assertThat(snapshotCaptor.getValue().getContent()).contains("\"physicalName\":\"orders\"");
        assertThat(snapshotCaptor.getValue().getChangeSummary())
                .isEqualTo("{\"created\":true,\"tables\":1,\"relationships\":0}");
        verify(auditRecorder).record(eq(2L), eq("CONNECTION_REVERSE_ENGINEERED"), eq("CONNECTION"),
                eq("11"), any());
    }

    @Test
    @DisplayName("리버스 — 같은 이름의 문서가 있으면 409 DUPLICATED_NAME")
    void reverseDuplicateName() throws SQLException {
        given(connectionRepository.findByIdAndWorkspaceId(11L, 7L)).willReturn(Optional.of(connection()));
        given(modelRepository.existsByWorkspaceIdAndName(7L, "가져온 문서")).willReturn(true);

        assertThatThrownBy(() -> service.reverse(2L, 7L, 11L,
                new ReverseEngineeringRequest("가져온 문서", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATED_NAME);
    }

    @Test
    @DisplayName("리버스 — 접속 실패는 502 CONNECTION_UNREACHABLE과 분류 문구")
    void reverseUnreachable() throws SQLException {
        given(connectionRepository.findByIdAndWorkspaceId(11L, 7L)).willReturn(Optional.of(connection()));
        given(modelRepository.existsByWorkspaceIdAndName(anyLong(), any())).willReturn(false);
        given(introspectors.forDbmsType("mysql")).willReturn(stubIntrospector);
        given(introspectors.open(any(), any(), anyInt(), any(), any(), any()))
                .willThrow(new SQLException("Connection refused", "08001"));

        assertThatThrownBy(() -> service.reverse(2L, 7L, 11L, new ReverseEngineeringRequest(null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONNECTION_UNREACHABLE);
    }

    @Test
    @DisplayName("리버스 — introspector가 없는 dbms는 400 INVALID_DBMS_TYPE")
    void reverseUnsupportedDbms() {
        given(connectionRepository.findByIdAndWorkspaceId(11L, 7L)).willReturn(Optional.of(connection()));
        given(modelRepository.existsByWorkspaceIdAndName(anyLong(), any())).willReturn(false);
        given(introspectors.forDbmsType("mysql")).willReturn(null);

        assertThatThrownBy(() -> service.reverse(2L, 7L, 11L, new ReverseEngineeringRequest(null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_DBMS_TYPE);
    }
}
