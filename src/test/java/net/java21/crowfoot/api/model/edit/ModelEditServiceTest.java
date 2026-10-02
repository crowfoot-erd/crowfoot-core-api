package net.java21.crowfoot.api.model.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.domaintype.repository.WorkspaceDomainTypeRepository;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.edit.EditRequests.ColumnItem;
import net.java21.crowfoot.api.model.edit.EditRequests.RequirementItem;
import net.java21.crowfoot.api.model.edit.EditRequests.RequirementsApply;
import net.java21.crowfoot.api.model.edit.EditRequests.SchemaApply;
import net.java21.crowfoot.api.model.edit.EditRequests.TableItem;
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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 문서 편집 API 서비스 단위 테스트 (08-core/17-model-edit.md Section 3) — 권한, 버전, 저장과 스냅샷, 감사.
 * 본체를 고치는 규칙은 {@link DocumentEditorTest}가 본다.
 */
@ExtendWith(MockitoExtension.class)
class ModelEditServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMPTY =
            "{\"schemaVersion\":1,\"model\":{\"tables\":[],\"relationships\":[]},\"diagram\":{\"nodes\":{},\"notes\":[],\"viewport\":null}}";

    @Mock
    private ModelRepository modelRepository;
    @Mock
    private ModelVersionRepository modelVersionRepository;
    @Mock
    private ModelVersionPruner modelVersionPruner;
    @Mock
    private WorkspaceDomainTypeRepository domainTypeRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;

    private ModelEditService service;

    @BeforeEach
    void setUp() {
        service = new ModelEditService(modelRepository, modelVersionRepository, modelVersionPruner, domainTypeRepository,
                roleChecker, auditRecorder, MAPPER);
    }

    private void model(String content, long version) {
        Model model = Mockito.mock(Model.class);
        Mockito.lenient().when(model.getId()).thenReturn(501L);
        Mockito.lenient().when(model.getName()).thenReturn("쇼핑몰 ERD");
        Mockito.lenient().when(model.getDatabaseType()).thenReturn("postgresql");
        Mockito.lenient().when(model.getVersion()).thenReturn(version);
        Mockito.lenient().when(model.getContent()).thenReturn(content);
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
    }

    private static SchemaApply users() {
        return new SchemaApply(null, "Claude(MCP): ERD 반영",
                List.of(new TableItem("users", null, "회원", null,
                        List.of(new ColumnItem("id", null, null, null, "BIGINT", null, null, null, null, null, true, null)),
                        List.of("id"), null, null, null)), null, null);
    }

    @Test
    @DisplayName("스키마 반영 — 버전을 1 올려 저장하고, 변경 요약과 메모를 단 스냅샷을 남긴다")
    void applySchema() {
        model(EMPTY, 7);
        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(7L), anyString(), any())).willReturn(1);

        ModelEditService.EditResult result = service.applySchema(2L, 77L, 501L, users());

        assertThat(result.version()).isEqualTo(8);
        assertThat(result.changed()).isTrue();
        assertThat(result.summary()).containsExactly(Map.of("kind", "table", "action", "add", "name", "users"));
        assertThat(result.warnings()).extracting(DocumentEditor.Warning::code).containsExactly("UNTRACED_TABLE");
        then(roleChecker).should().requireEditor(2L, 77L);
        ArgumentCaptor<ModelVersion> snapshot = ArgumentCaptor.forClass(ModelVersion.class);
        then(modelVersionRepository).should().save(snapshot.capture());
        assertThat(snapshot.getValue().getVersion()).isEqualTo(8);
        assertThat(snapshot.getValue().getMemo()).isEqualTo("Claude(MCP): ERD 반영");
        JsonNode summary = MAPPER.readTree(snapshot.getValue().getChangeSummary());
        assertThat(summary.path("items").get(0).path("kind").asText()).isEqualTo("table");
        assertThat(summary.path("layoutOnly").asBoolean()).isFalse();
        // 저장한 본체에 위치는 없다 — 에디터가 열 때 배치한다
        JsonNode saved = MAPPER.readTree(snapshot.getValue().getContent());
        assertThat(saved.path("model").path("tables").get(0).path("physicalName").asText()).isEqualTo("users");
        assertThat(saved.path("diagram").path("nodes").isEmpty()).isTrue();
        then(auditRecorder).should().record(eq(2L), eq("MODEL_SCHEMA_APPLIED"), eq("MODEL"), eq("501"), any());
        then(modelVersionPruner).should().prune(501L, 8L, 2L);
    }

    @Test
    @DisplayName("바뀐 것이 없으면 저장하지 않는다 — 버전도 그대로다")
    void unchanged() {
        model(EMPTY, 7);
        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(7L), anyString(), any())).willReturn(1);
        service.applySchema(2L, 77L, 501L, users());
        ArgumentCaptor<ModelVersion> snapshot = ArgumentCaptor.forClass(ModelVersion.class);
        then(modelVersionRepository).should().save(snapshot.capture());
        Mockito.reset(modelRepository, modelVersionRepository, auditRecorder);

        model(snapshot.getValue().getContent(), 8);
        ModelEditService.EditResult again = service.applySchema(2L, 77L, 501L, users());

        assertThat(again.changed()).isFalse();
        assertThat(again.version()).isEqualTo(8);
        assertThat(again.summary()).isEmpty();
        then(modelRepository).should(never()).updateContentIfVersionMatches(anyLong(), anyLong(), anyLong(), anyString(), any());
        then(modelVersionRepository).should(never()).save(any());
        then(auditRecorder).should(never()).record(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("버전 충돌 — baseVersion이 다르거나, 읽은 뒤에 다른 저장이 끼어들었다")
    void versionConflict() {
        model(EMPTY, 7);
        SchemaApply stale = new SchemaApply(6L, null, users().tables(), null, null);
        assertThatThrownBy(() -> service.applySchema(2L, 77L, 501L, stale))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));

        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(7L), anyString(), any())).willReturn(0);
        assertThatThrownBy(() -> service.applySchema(2L, 77L, 501L, users()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        then(modelVersionRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("입력이 틀리면 사유를 돌려주고 아무것도 저장하지 않는다")
    void invalidInput() {
        model(EMPTY, 7);
        SchemaApply bad = new SchemaApply(null, null,
                List.of(new TableItem("users", null, null, null,
                        List.of(new ColumnItem("id", null, null, null, "VARCHAR2", null, null, null, null, null, null, null)), null, null, null, null)),
                null, null);

        assertThatThrownBy(() -> service.applySchema(2L, 77L, 501L, bad))
                .isInstanceOfSatisfying(EditValidationException.class,
                        ex -> assertThat(ex.errors()).extracting(error -> error.field()).containsExactly("tables[0].columns[0].dataType"));
        then(modelRepository).should(never()).updateContentIfVersionMatches(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("권한과 대상 — Editor 미만은 403, 없는 문서는 404, 읽을 수 없는 본체는 409")
    void guards() {
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED)).given(roleChecker).requireEditor(3L, 77L);
        assertThatThrownBy(() -> service.applySchema(3L, 77L, 501L, users()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));

        given(modelRepository.findByIdAndWorkspaceId(999L, 77L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.applySchema(2L, 77L, 999L, users()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODEL_NOT_FOUND));

        model("{\"model\":{\"tables\":{}}}", 1);
        assertThatThrownBy(() -> service.outline(2L, 77L, 501L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONTENT_UNREADABLE));
    }

    @Test
    @DisplayName("요구사항 반영과 개요 — 1차 구현 이전의 빈 문서도 고칠 수 있다")
    void requirementsOnLegacyDocument() {
        model("{\"tables\":[],\"relationships\":[]}", 0);
        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(0L), anyString(), any())).willReturn(1);

        ModelEditService.EditResult result = service.applyRequirements(2L, 77L, 501L,
                new RequirementsApply(null, null, List.of(new RequirementItem(null, "주문 생성", null, "confirmed", null, "주문", null))));

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.requirements().get("pending")).isEqualTo(List.of("REQ-001"));
        then(auditRecorder).should().record(eq(2L), eq("MODEL_REQUIREMENTS_APPLIED"), eq("MODEL"), eq("501"), any());

        ArgumentCaptor<ModelVersion> snapshot = ArgumentCaptor.forClass(ModelVersion.class);
        then(modelVersionRepository).should().save(snapshot.capture());
        model(snapshot.getValue().getContent(), 1);
        Map<String, Object> outline = service.outline(2L, 77L, 501L);
        assertThat(outline.get("name")).isEqualTo("쇼핑몰 ERD");
        assertThat(outline.get("databaseType")).isEqualTo("postgresql");
        assertThat((List<?>) outline.get("requirements")).hasSize(1);
        then(roleChecker).should().requireMember(2L, 77L);
    }
}
