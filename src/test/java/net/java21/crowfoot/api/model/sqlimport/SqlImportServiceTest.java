package net.java21.crowfoot.api.model.sqlimport;

import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
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

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * SQL Import 단위 테스트 (08-core/02-model.md Section 1.12) — 파서·조립기는 실물(순수함수)로
 * 돌리고 저장 계층만 모킹. v0 요약 source:sql·sourceConnectionId 미연관·이름 기본값·
 * CREATE TABLE 0개 400·비활성 databaseType 400·미리보기 개수 일치를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SqlImportServiceTest {

    /** members ← orders(inline REFERENCES ON DELETE CASCADE) — 테이블 2·관계 1 */
    private static final String DDL = """
            CREATE TABLE members (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              email VARCHAR(191) NOT NULL,
              UNIQUE KEY uk_members_email (email)
            );
            CREATE TABLE orders (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              member_id BIGINT NOT NULL REFERENCES members (id) ON DELETE CASCADE
            );
            """;

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
    private DatabaseTypeRepository databaseTypeRepository;
    @Mock
    private Introspectors introspectors;

    private SqlImportService service;

    @BeforeEach
    void setUp() {
        service = new SqlImportService(modelRepository, modelDiagramRepository, modelVersionRepository,
                modelVersionPruner, userRepository, roleChecker, auditRecorder, databaseTypeRepository,
                introspectors, new ReverseContentAssembler());
    }

    /** 활성 mysql 스텁 — 타입 검증 통과 + commonTypeCode는 실물 MySqlIntrospector가 맡는다.
     *  introspector 스텁은 CREATE TABLE 0개처럼 도달 전에 실패하는 테스트도 있어 lenient다. */
    private void stubActiveType() {
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("mysql"))
                .willReturn(Optional.of(new DatabaseType("mysql", "MySQL", true)));
        org.mockito.Mockito.lenient().when(introspectors.forDbmsType("mysql"))
                .thenReturn(new net.java21.crowfoot.api.connection.introspect.MySqlIntrospector());
    }

    private void stubSave() {
        given(modelRepository.save(any())).willAnswer(inv -> {
            Model model = inv.getArgument(0);
            ReflectionTestUtils.setField(model, "id", 501L);
            ReflectionTestUtils.setField(model, "createdAt", Instant.now());
            ReflectionTestUtils.setField(model, "updatedAt", Instant.now());
            return model;
        });
    }

    @Test
    @DisplayName("생성 — 이름 기본값 'SQL ERD'·content 저장·sourceConnectionId 미연관·감사 MODEL_SQL_IMPORTED")
    void importDocumentCreatesModelFromDdl() {
        stubActiveType();
        given(modelRepository.existsByWorkspaceIdAndName(7L, "SQL ERD")).willReturn(false);
        stubSave();

        SqlImportResponse response = service.importDocument(2L, 7L,
                new SqlImportRequest(null, "운영 DDL 백업", "mysql", DDL));

        assertThat(response.model().name()).isEqualTo("SQL ERD");
        assertThat(response.model().databaseType()).isEqualTo("mysql");
        assertThat(response.model().sourceConnectionId()).isNull(); // DB 동기화 버튼 미노출 근거
        assertThat(response.tableCount()).isEqualTo(2);
        assertThat(response.relationshipCount()).isEqualTo(1);
        assertThat(response.skipped()).isEmpty();

        ArgumentCaptor<Model> modelCaptor = ArgumentCaptor.forClass(Model.class);
        verify(modelRepository).save(modelCaptor.capture());
        assertThat(modelCaptor.getValue().getContent()).contains("\"physicalName\":\"members\"");
        assertThat(modelCaptor.getValue().getContent()).contains("\"schemaVersion\":1");
        assertThat(modelCaptor.getValue().getSourceConnectionId()).isNull();

        ArgumentCaptor<ModelDiagram> diagramCaptor = ArgumentCaptor.forClass(ModelDiagram.class);
        verify(modelDiagramRepository).save(diagramCaptor.capture());
        assertThat(diagramCaptor.getValue().isMain()).isTrue();

        // v0 스냅샷 — SQL 가져오기 요약은 source:sql을 심는다(웹 배지 분기, 02-model.md 1.12)
        ArgumentCaptor<ModelVersion> snapshotCaptor = ArgumentCaptor.forClass(ModelVersion.class);
        verify(modelVersionRepository).save(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getModelId()).isEqualTo(501L);
        assertThat(snapshotCaptor.getValue().getVersion()).isZero();
        assertThat(snapshotCaptor.getValue().getChangeSummary())
                .isEqualTo("{\"created\":true,\"source\":\"sql\",\"tables\":2,\"relationships\":1}");

        verify(modelVersionPruner).prune(501L, 0, 2L);
        verify(auditRecorder).record(eq(2L), eq("MODEL_SQL_IMPORTED"), eq("MODEL"), eq("501"),
                any());
        verify(roleChecker).requireEditor(2L, 7L);
    }

    @Test
    @DisplayName("생성 — 요청 이름 우선·중복은 409")
    void importDocumentNameRules() {
        stubActiveType();
        given(modelRepository.existsByWorkspaceIdAndName(7L, "쇼핑 ERD")).willReturn(false);
        stubSave();
        assertThat(service.importDocument(2L, 7L,
                        new SqlImportRequest("쇼핑 ERD", null, "mysql", DDL)).model().name())
                .isEqualTo("쇼핑 ERD");

        given(modelRepository.existsByWorkspaceIdAndName(7L, "SQL ERD")).willReturn(true);
        assertThatThrownBy(() -> service.importDocument(2L, 7L,
                new SqlImportRequest(null, null, "mysql", DDL)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATED_NAME));
    }

    @Test
    @DisplayName("CREATE TABLE 0개는 400 SQL_IMPORT_NO_TABLES — 문서가 만들어지지 않는다")
    void importDocumentRequiresTables() {
        stubActiveType();
        assertThatThrownBy(() -> service.importDocument(2L, 7L,
                new SqlImportRequest(null, null, "mysql", "DROP TABLE x; INSERT INTO t VALUES (1);")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SQL_IMPORT_NO_TABLES));
        verify(modelRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("비활성 databaseType은 400 INVALID_REQUEST — 생성 관례와 같다")
    void importDocumentRejectsInactiveDatabaseType() {
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("oracle")).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.importDocument(2L, 7L,
                new SqlImportRequest(null, null, "oracle", DDL)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("미리보기 — 저장 없이 테이블 요약·PK 컬럼·FK 수, 개수는 생성과 같은 경로다")
    void previewSummarizesWithoutSaving() {
        stubActiveType();

        SqlImportPreviewResponse preview = service.preview(2L, 7L,
                new SqlImportPreviewRequest("mysql", DDL));

        assertThat(preview.databaseType()).isEqualTo("mysql");
        assertThat(preview.tableCount()).isEqualTo(2);
        assertThat(preview.relationshipCount()).isEqualTo(1);
        assertThat(preview.tables()).hasSize(2);
        SqlImportPreviewResponse.PreviewTable members = preview.tables().get(0);
        assertThat(members.name()).isEqualTo("members");
        assertThat(members.columnCount()).isEqualTo(2);
        assertThat(members.primaryKeyColumns()).containsExactly("id");
        assertThat(members.foreignKeyCount()).isZero();
        SqlImportPreviewResponse.PreviewTable orders = preview.tables().get(1);
        assertThat(orders.foreignKeyCount()).isEqualTo(1);
        assertThat(preview.skipped()).isEmpty();

        verify(modelRepository, org.mockito.Mockito.never()).save(any());
        verify(modelVersionRepository, org.mockito.Mockito.never()).save(any());
        verify(roleChecker).requireEditor(2L, 7L);
    }

    @Test
    @DisplayName("미리보기 — 읽지 못한 문장은 skipped 경고로 돌아간다(전체 실패 아님)")
    void previewReportsSkippedStatements() {
        stubActiveType();

        SqlImportPreviewResponse preview = service.preview(2L, 7L,
                new SqlImportPreviewRequest("mysql",
                        "CREATE INDEX idx_m_email ON members (email);\n" + DDL));

        assertThat(preview.tableCount()).isEqualTo(2);
        assertThat(preview.skipped()).anyMatch(s -> s.startsWith("CREATE INDEX"));
    }
}
