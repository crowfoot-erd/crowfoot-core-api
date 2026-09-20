package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.dto.CreateModelRequest;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.ModelSummaryResponse;
import net.java21.crowfoot.api.model.dto.ModelVersionResponse;
import net.java21.crowfoot.api.model.dto.SaveContentRequest;
import net.java21.crowfoot.api.model.dto.SaveContentResponse;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.model.repository.ModelDiagramRepository;
import net.java21.crowfoot.api.model.repository.ModelQueryRepository;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.ListApiResponse;
import tools.jackson.databind.ObjectMapper;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ERD 문서 API 단위 테스트 (08-core/02-model.md Section 1) —
 * 생성 2-INSERT(모델·main 다이어그램)·databaseType 코드 검증·이름 중복·목록 페이징·
 * content 저장(낙관적 잠금 409·JSON/5MB 검증)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ModelServiceTest {

    @Mock
    private ModelRepository modelRepository;
    @Mock
    private ModelDiagramRepository modelDiagramRepository;
    @Mock
    private ModelQueryRepository modelQueryRepository;
    @Mock
    private DatabaseTypeRepository databaseTypeRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;

    private ModelService modelService;

    @BeforeEach
    void setUp() {
        // ObjectMapper는 실물 — JSON 파싱 검증 자체가 테스트 대상이다
        modelService = new ModelService(modelRepository, modelDiagramRepository, modelQueryRepository,
                databaseTypeRepository, userRepository, roleChecker, auditRecorder, new ObjectMapper());
    }


    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode jsonNode(String json) {
        return MAPPER.readValue(json, JsonNode.class);
    }

    private static CreateModelRequest request() {
        return new CreateModelRequest("주문 서비스 ERD", "설명", "postgresql");
    }

    /** 빈 Canonical 문서 v1 (1.5.1) — 생성 초기값과 동일 */
    private static final String V1_EMPTY =
            "{\"schemaVersion\":1,\"model\":{\"tables\":[],\"relationships\":[]},\"diagram\":{\"nodes\":{},\"notes\":[],\"viewport\":null}}";

    private static Model persisted() {
        Model model = new Model(77L, "주문 서비스 ERD", "설명", "postgresql",
                V1_EMPTY, 7L);
        ReflectionTestUtils.setField(model, "id", 501L);
        return model;
    }

    @Test
    @DisplayName("생성은 모델·main 다이어그램을 넣고 감사를 남긴다 — 빈 문서·빈 레이아웃으로 시작")
    void createPersistsModelWithMainDiagram() {
        // given
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new net.java21.crowfoot.api.model.domain.DatabaseType("postgresql", "PostgreSQL", true)));
        given(modelRepository.existsByWorkspaceIdAndName(77L, "주문 서비스 ERD")).willReturn(false);
        given(modelRepository.save(any(Model.class))).willAnswer(inv -> persisted());
        User creator = new User("marco@x.com", "marco", true);
        ReflectionTestUtils.setField(creator, "id", 7L);
        given(userRepository.findById(7L)).willReturn(Optional.of(creator));

        // when
        ModelResponse response = modelService.create(7L, 77L, request());

        // then: main 다이어그램 자동 생성
        ArgumentCaptor<ModelDiagram> diagram = ArgumentCaptor.forClass(ModelDiagram.class);
        verify(modelDiagramRepository).save(diagram.capture());
        assertThat(diagram.getValue().getModelId()).isEqualTo(501L);
        assertThat(diagram.getValue().getName()).isEqualTo("main");
        assertThat(diagram.getValue().isMain()).isTrue();
        assertThat(diagram.getValue().getLayoutContent()).contains("nodes").contains("viewport");

        // then: 응답 — databaseType·빈 content·생성자 이름
        assertThat(response.modelId()).isEqualTo("501");
        assertThat(response.databaseType()).isEqualTo("postgresql");
        assertThat(response.version()).isZero();
        assertThat(response.content()).isEqualTo(V1_EMPTY);
        assertThat(response.createdBy().name()).isEqualTo("marco");

        // then: 감사
        verify(auditRecorder).record(eq(7L), eq("MODEL_CREATED"), eq("MODEL"), eq("501"), any());
    }

    @Test
    @DisplayName("비활성·미등록 databaseType 생성은 400으로 거부된다 — 저장 없음")
    void createRejectsUnknownDatabaseType() {
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("oracle")).willReturn(Optional.empty());

        assertThatThrownBy(() -> modelService.create(7L, 77L,
                new CreateModelRequest("주문 ERD", null, "oracle")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verify(modelRepository, never()).save(any());
        verify(modelDiagramRepository, never()).save(any());
    }

    @Test
    @DisplayName("Workspace 내 이름 중복 생성은 409 DUPLICATED_NAME이다")
    void createRejectsDuplicateName() {
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new net.java21.crowfoot.api.model.domain.DatabaseType("postgresql", "PostgreSQL", true)));
        given(modelRepository.existsByWorkspaceIdAndName(77L, "주문 서비스 ERD")).willReturn(true);

        assertThatThrownBy(() -> modelService.create(7L, 77L, request()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATED_NAME));

        verify(modelRepository, never()).save(any());
    }

    @Test
    @DisplayName("목록은 요약 행(content 제외)을 페이징 포맷으로 내린다")
    void listReturnsPagedSummaries() {
        // given
        given(modelQueryRepository.count(77L, null)).willReturn(2L);
        given(modelQueryRepository.search(77L, null, 1, 20)).willReturn(List.of(
                new ModelQueryRepository.ModelRow(501L, 77L, "주문 서비스 ERD", "설명", "postgresql",
                        301L, 3L, 7L, "marco", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z")),
                new ModelQueryRepository.ModelRow(502L, 77L, "회원 서비스 ERD", null, "mysql",
                        null, 1L, 8L, "jenny", Instant.parse("2026-09-03T00:00:00Z"), Instant.parse("2026-09-03T00:00:00Z"))));

        // when
        ListApiResponse<ModelSummaryResponse> response = modelService.list(7L, 77L, null, null, null);

        // then
        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.responses()).hasSize(2);
        ModelSummaryResponse first = response.responses().get(0);
        assertThat(first.modelId()).isEqualTo("501");
        assertThat(first.databaseType()).isEqualTo("postgresql");
        assertThat(first.sourceConnectionId()).isEqualTo("301");
        assertThat(first.createdBy().name()).isEqualTo("marco");
        assertThat(response.responses().get(1).sourceConnectionId()).isNull();
        verify(roleChecker).requireMember(7L, 77L);
    }

    @Test
    @DisplayName("총 개수가 0이면 검색하지 않고 빈 페이지를 응답한다")
    void listSkipsSearchWhenEmpty() {
        given(modelQueryRepository.count(77L, "없는키워드")).willReturn(0L);

        ListApiResponse<ModelSummaryResponse> response = modelService.list(7L, 77L, "없는키워드", 1, 20);

        assertThat(response.totalCount()).isZero();
        assertThat(response.responses()).isEmpty();
        verify(modelQueryRepository, never()).search(anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("메타 변경은 이름·설명만 바꾼다 — version은 그대로, 자기 이름은 중복 검사 제외")
    void patchChangesNameAndDescriptionWithoutVersionBump() {
        // given
        Model model = persisted();
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
        given(modelRepository.existsByWorkspaceIdAndNameAndIdNot(77L, "주문 서비스 ERD v2", 501L)).willReturn(false);
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        // when
        ModelSummaryResponse response = modelService.patch(7L, 77L, 501L,
                jsonNode("{\"name\":\"주문 서비스 ERD v2\",\"description\":null}"));

        // then
        assertThat(response.name()).isEqualTo("주문 서비스 ERD v2");
        assertThat(response.description()).isNull();
        assertThat(response.version()).isZero(); // 메타 변경은 version 미증가
        verify(auditRecorder).record(eq(7L), eq("MODEL_UPDATED"), eq("MODEL"), eq("501"), any());
    }

    @Test
    @DisplayName("메타 변경의 이름 중복(다른 모델)은 409 DUPLICATED_NAME이다")
    void patchRejectsDuplicateName() {
        Model model = persisted();
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
        given(modelRepository.existsByWorkspaceIdAndNameAndIdNot(77L, "회원 서비스 ERD", 501L)).willReturn(true);

        assertThatThrownBy(() -> modelService.patch(7L, 77L, 501L,
                jsonNode("{\"name\":\"회원 서비스 ERD\"}")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATED_NAME));
    }

    @Test
    @DisplayName("삭제는 Owner 검사 후 물리 삭제하고 감사를 남긴다")
    void deleteRequiresOwnerAndDeletes() {
        Model model = persisted();
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));

        modelService.delete(7L, 77L, 501L);

        verify(roleChecker).requireOwner(7L, 77L);
        verify(modelRepository).deleteById(501L);
        verify(auditRecorder).record(eq(7L), eq("MODEL_DELETED"), eq("MODEL"), eq("501"), any());
    }

    @Test
    @DisplayName("상세는 content를 통째로 내려준다 — Viewer 멤버 검사를 거친다")
    void detailReturnsContent() {
        // given
        Model model = persisted();
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
        User creator = new User("marco@x.com", "marco", true);
        ReflectionTestUtils.setField(creator, "id", 7L);
        given(userRepository.findById(7L)).willReturn(Optional.of(creator));

        // when
        ModelResponse response = modelService.detail(7L, 77L, 501L);

        // then
        verify(roleChecker).requireMember(7L, 77L);
        assertThat(response.modelId()).isEqualTo("501");
        assertThat(response.databaseType()).isEqualTo("postgresql");
        assertThat(response.content()).isEqualTo(V1_EMPTY);
        assertThat(response.createdBy().name()).isEqualTo("marco");
    }

    @Test
    @DisplayName("상세도 없는 모델·다른 Workspace 소속은 404 MODEL_NOT_FOUND다")
    void detailRejectsUnknownModel() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> modelService.detail(7L, 77L, 501L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_NOT_FOUND));
    }

    @Test
    @DisplayName("버전 경량 조회는 version·갱신 일시만 내린다 — 프로젝션 행, Viewer 멤버 검사")
    void versionReturnsProjectionRow() {
        // given
        Instant updatedAt = Instant.parse("2026-09-14T05:00:00Z");
        given(modelRepository.findVersionRowByIdAndWorkspaceId(501L, 77L))
                .willReturn(Collections.singletonList(new Object[] {12L, updatedAt}));

        // when
        ModelVersionResponse response = modelService.version(7L, 77L, 501L);

        // then
        verify(roleChecker).requireMember(7L, 77L);
        assertThat(response.version()).isEqualTo(12);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("버전 조회도 없는 모델은 404 MODEL_NOT_FOUND다 — 감사 없음(폴링)")
    void versionRejectsUnknownModel() {
        given(modelRepository.findVersionRowByIdAndWorkspaceId(501L, 77L)).willReturn(List.of());

        assertThatThrownBy(() -> modelService.version(7L, 77L, 501L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_NOT_FOUND));
    }

    @Test
    @DisplayName("다른 Workspace 소속 모델 접근은 404 MODEL_NOT_FOUND로 은닉된다")
    void patchRejectsModelOfOtherWorkspace() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> modelService.patch(7L, 77L, 501L, jsonNode("{\"name\":\"X\"}")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_NOT_FOUND));
    }

    @Test
    @DisplayName("content 저장은 조건부 갱신 1행이면 version+1·갱신 일시를 응답하고 감사를 남긴다")
    void saveContentBumpsVersionAndAudits() {
        // given
        String content = "{\"schemaVersion\":1,\"model\":{\"tables\":[],\"relationships\":[]},\"diagram\":{}}";
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(persisted()));
        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(3L), eq(content), any())).willReturn(1);
        Model saved = persisted(); // 갱신 후 재조회 — version 4로 증가
        ReflectionTestUtils.setField(saved, "version", 4L);
        ReflectionTestUtils.setField(saved, "updatedAt", Instant.parse("2026-09-12T05:00:00Z"));
        given(modelRepository.findById(501L)).willReturn(Optional.of(saved));

        // when
        SaveContentResponse response = modelService.saveContent(7L, 77L, 501L,
                new SaveContentRequest(3, content));

        // then
        assertThat(response.version()).isEqualTo(4);
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-09-12T05:00:00Z"));
        verify(roleChecker).requireEditor(7L, 77L);
        verify(auditRecorder).record(eq(7L), eq("MODEL_UPDATED"), eq("MODEL"), eq("501"), any());
    }

    @Test
    @DisplayName("버전 불일치 저장은 409 VERSION_CONFLICT이다 — 갱신 0행, 감사 없음")
    void saveContentRejectsVersionMismatch() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(persisted()));
        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(2L), anyString(), any())).willReturn(0);

        assertThatThrownBy(() -> modelService.saveContent(7L, 77L, 501L,
                new SaveContentRequest(2, "{\"a\":1}")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));

        verify(auditRecorder, never()).record(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("비JSON content 저장은 400 INVALID_REQUEST이다 — 갱신 실행 없음")
    void saveContentRejectsNonJson() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(persisted()));

        assertThatThrownBy(() -> modelService.saveContent(7L, 77L, 501L,
                new SaveContentRequest(3, "not-json{")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verify(modelRepository, never()).updateContentIfVersionMatches(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("5MB(UTF-8 바이트) 초과 content는 400 INVALID_REQUEST이다")
    void saveContentRejectsOversized() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(persisted()));
        String oversized = "{\"a\":\"" + "x".repeat(5 * 1024 * 1024) + "\"}";

        assertThatThrownBy(() -> modelService.saveContent(7L, 77L, 501L,
                new SaveContentRequest(3, oversized)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verify(modelRepository, never()).updateContentIfVersionMatches(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("다른 Workspace 소속 모델의 content 저장은 404 MODEL_NOT_FOUND로 은닉된다")
    void saveContentRejectsModelOfOtherWorkspace() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> modelService.saveContent(7L, 77L, 501L,
                new SaveContentRequest(0, "{}")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_NOT_FOUND));
    }
}
