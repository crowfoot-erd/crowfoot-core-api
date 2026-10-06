package net.java21.crowfoot.api.model.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.domaintype.repository.WorkspaceDomainTypeRepository;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.edit.EditRequests.RequirementItem;
import net.java21.crowfoot.api.model.edit.EditRequests.RequirementsSync;
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
 * 요구사항 동기화 (08-core/17-model-edit.md Section 3.5 — v1.36). 맞추기(code·제목), 차이, 빠짐, 지문, 적용을 본다.
 */
@ExtendWith(MockitoExtension.class)
class RequirementSyncServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 요구사항 셋(REQ-001 회원 가입 — users 연결, REQ-002 주문 생성, REQ-003 쿠폰 발급)과 테이블 users */
    private static final String CONTENT = """
            {"schemaVersion":1,
             "model":{"tables":[{"id":"t-users","physicalName":"users","logicalName":"회원","columns":[
                 {"id":"c-id","physicalName":"id","dataType":"BIGINT","nullable":false,"autoIncrement":true,"logicalName":"id"}],
                 "primaryKey":{"name":"users_pk","columnIds":["c-id"]},"uniques":[],"indexes":[]}],"relationships":[]},
             "diagram":{"nodes":{},"notes":[],"viewport":null,
               "areas":[{"id":"a-member","name":"회원","color":"blue"}],
               "requirements":[
                 {"id":"r1","code":"REQ-001","areaId":"a-member","scope":"tables","title":"회원 가입","description":"이메일로 가입한다",
                  "status":"confirmed","revision":1,"appliedRevision":1,"tableIds":["t-users"]},
                 {"id":"r2","code":"REQ-002","areaId":null,"scope":"tables","title":"주문 생성","description":"회원만 주문한다",
                  "status":"draft","revision":1,"appliedRevision":0,"tableIds":[]},
                 {"id":"r3","code":"REQ-003","areaId":null,"scope":"tables","title":"쿠폰 발급","description":"",
                  "status":"draft","revision":1,"appliedRevision":0,"tableIds":[]}]}}""";

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
        Mockito.lenient().when(model.getDatabaseType()).thenReturn("postgresql");
        Mockito.lenient().when(model.getVersion()).thenReturn(version);
        Mockito.lenient().when(model.getContent()).thenReturn(content);
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
    }

    /** 기준 목록 — REQ-001은 code로 내용 수정, 주문 생성은 제목으로 그대로, 리뷰 작성은 추가. 쿠폰 발급은 빠졌다 */
    private static List<RequirementItem> source() {
        return List.of(
                new RequirementItem("REQ-001", "회원 가입", "이메일 또는 소셜 계정으로 가입한다", null, null, null, null),
                new RequirementItem(null, "  주문   생성 ", "회원만 주문한다\r\n", null, null, null, null),
                new RequirementItem(null, "리뷰 작성", "구매한 회원만 리뷰를 쓴다", "draft", null, "주문", null));
    }

    @Test
    @DisplayName("계획 — code로, 없으면 제목(공백·대소문자 무시)으로 맞추고 추가·수정·빠짐·그대로를 나눈다. 문서는 바꾸지 않는다")
    void plan() {
        model(CONTENT, 5);

        ModelEditService.RequirementsSyncPlan plan = service.planRequirementsSync(2L, 77L, 501L,
                new RequirementsSync(null, source(), null, null));

        assertThat(plan.documentVersion()).isEqualTo(5);
        assertThat(plan.updated()).singleElement().satisfies(updated -> {
            assertThat(updated.code()).isEqualTo("REQ-001");
            assertThat(updated.matchedBy()).isEqualTo("code");
            assertThat(updated.changes()).containsExactly(new RequirementSync.FieldChange(
                    "description", "이메일로 가입한다", "이메일 또는 소셜 계정으로 가입한다"));
        });
        assertThat(plan.unchanged()).isEqualTo(1); // 주문 생성 — 줄바꿈 표기만 달랐다
        assertThat(plan.added()).extracting(RequirementSync.Added::title).containsExactly("리뷰 작성");
        assertThat(plan.missing()).singleElement().satisfies(missing -> {
            assertThat(missing.code()).isEqualTo("REQ-003");
            assertThat(missing.action()).isEqualTo("drop");
        });
        assertThat(plan.changeCount()).isEqualTo(3);
        assertThat(plan.planFingerprint()).hasSize(64);
        then(roleChecker).should().requireEditor(2L, 77L);
        then(modelRepository).should(never()).updateContentIfVersionMatches(anyLong(), anyLong(), anyLong(), anyString(), any());

        // 지우기를 승인해도 지문은 같다 — 적용 방식만 다르다
        ModelEditService.RequirementsSyncPlan removing = service.planRequirementsSync(2L, 77L, 501L,
                new RequirementsSync(null, source(), true, null));
        assertThat(removing.missing().get(0).action()).isEqualTo("remove");
        assertThat(removing.planFingerprint()).isEqualTo(plan.planFingerprint());
    }

    @Test
    @DisplayName("적용 — 지문이 같으면 한 버전으로 저장한다. 바뀐 요구사항은 반영 대기, 빠진 요구사항은 dropped, 새 요구사항은 다음 번호")
    void apply() {
        model(CONTENT, 5);
        String fingerprint = service.planRequirementsSync(2L, 77L, 501L, new RequirementsSync(null, source(), null, null))
                .planFingerprint();
        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(5L), anyString(), any())).willReturn(1);

        ModelEditService.RequirementsSyncResult result = service.applyRequirementsSync(2L, 77L, 501L,
                new RequirementsSync("회의록 반영", source(), null, fingerprint));

        assertThat(result.result().version()).isEqualTo(6);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.dropped()).isEqualTo(1);
        assertThat(result.removed()).isZero();
        ArgumentCaptor<ModelVersion> snapshot = ArgumentCaptor.forClass(ModelVersion.class);
        then(modelVersionRepository).should().save(snapshot.capture());
        assertThat(snapshot.getValue().getMemo()).isEqualTo("회의록 반영");
        JsonNode requirements = MAPPER.readTree(snapshot.getValue().getContent()).path("diagram").path("requirements");
        assertThat(requirements.get(0).path("revision").asInt()).isEqualTo(2); // 내용이 바뀌었다 — 반영 대기
        assertThat(requirements.get(1).path("revision").asInt()).isEqualTo(1);
        assertThat(requirements.get(1).path("description").asText()).isEqualTo("회원만 주문한다");
        assertThat(requirements.get(2).path("status").asText()).isEqualTo("dropped");
        assertThat(requirements.get(3).path("code").asText()).isEqualTo("REQ-004");
        assertThat(requirements.get(3).path("title").asText()).isEqualTo("리뷰 작성");
        then(auditRecorder).should().record(eq(2L), eq("MODEL_REQUIREMENTS_SYNCED"), eq("MODEL"), eq("501"), any());
    }

    @Test
    @DisplayName("지우기를 승인하면 빠진 요구사항을 지운다")
    void applyWithRemovals() {
        model(CONTENT, 5);
        String fingerprint = service.planRequirementsSync(2L, 77L, 501L, new RequirementsSync(null, source(), true, null))
                .planFingerprint();
        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(5L), anyString(), any())).willReturn(1);

        ModelEditService.RequirementsSyncResult result = service.applyRequirementsSync(2L, 77L, 501L,
                new RequirementsSync(null, source(), true, fingerprint));

        assertThat(result.removed()).isEqualTo(1);
        ArgumentCaptor<ModelVersion> snapshot = ArgumentCaptor.forClass(ModelVersion.class);
        then(modelVersionRepository).should().save(snapshot.capture());
        JsonNode requirements = MAPPER.readTree(snapshot.getValue().getContent()).path("diagram").path("requirements");
        assertThat(requirements).extracting(node -> node.path("code").asText()).containsExactly("REQ-001", "REQ-002", "REQ-004");
    }

    @Test
    @DisplayName("지문이 다르면 409 REQUIREMENTS_SYNC_PLAN_CHANGED — 저장하지 않는다")
    void fingerprintMismatch() {
        model(CONTENT, 5);
        assertThatThrownBy(() -> service.applyRequirementsSync(2L, 77L, 501L,
                new RequirementsSync(null, source(), null, "0".repeat(64))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REQUIREMENTS_SYNC_PLAN_CHANGED));
        then(modelRepository).should(never()).updateContentIfVersionMatches(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("검증 — 빈 목록은 거부하고, 없는 테이블은 계획에서 items[n] 위치로 알린다")
    void validation() {
        model(CONTENT, 5);
        assertThatThrownBy(() -> service.planRequirementsSync(2L, 77L, 501L, new RequirementsSync(null, List.of(), null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.planRequirementsSync(2L, 77L, 501L, new RequirementsSync(null,
                List.of(new RequirementItem(null, "결제", null, null, null, null, List.of("payments"))), null, null)))
                .isInstanceOfSatisfying(EditValidationException.class, e -> assertThat(e.errors())
                        .anySatisfy(error -> assertThat(error.field()).startsWith("items[0].tables")));
    }

    @Test
    @DisplayName("dropped였던 요구사항이 다시 나타나면 draft로 되살린다")
    void revivesDropped() {
        model(CONTENT.replaceFirst("(\"쿠폰 발급\",\"description\":\"\",\\s*\"status\":)\"draft\"", "$1\"dropped\""), 5);

        ModelEditService.RequirementsSyncPlan plan = service.planRequirementsSync(2L, 77L, 501L, new RequirementsSync(null,
                List.of(new RequirementItem(null, "쿠폰 발급", null, null, null, null, null)), null, null));

        assertThat(plan.updated()).singleElement().satisfies(updated ->
                assertThat(updated.changes()).containsExactly(new RequirementSync.FieldChange("status", "dropped", "draft")));
    }
}
