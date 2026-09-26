package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.config.AppProperties;
import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.dto.CloneFromTemplateRequest;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.TemplateSummaryResponse;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.model.repository.ModelDiagramRepository;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.term.domain.WorkspaceTerm;
import net.java21.crowfoot.api.term.repository.WorkspaceTermRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 템플릿 API 단위 테스트 (08-core/09-templates.md) — 공개 목록(설정 은닉·카운트 산출·활성 공유
 * 최근 1건 조인)과 복제(원천 검증·이름 규칙·저장 시퀀스·v0 요약·감사 액션·도메인 표준 사전 동반)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    private static final long USER_ID = 7L;
    private static final long TARGET_WS = 77L;
    private static final long TEMPLATE_WS = 34L;
    private static final Instant PAST = Instant.parse("2026-09-01T00:00:00Z");
    private static final String CONTENT =
            "{\"schemaVersion\":1,\"model\":{\"tables\":[{},{},{}],\"relationships\":[{}]}}";
    /** 사전 동반 검증용 — 테이블 member(컬럼 member_id·email)·orders(컬럼 order_no) */
    private static final String TERM_CONTENT = "{\"schemaVersion\":1,\"model\":{\"tables\":["
            + "{\"physicalName\":\"member\",\"columns\":[{\"physicalName\":\"member_id\"},{\"physicalName\":\"email\"}]},"
            + "{\"physicalName\":\"orders\",\"columns\":[{\"physicalName\":\"order_no\"}]}],"
            + "\"relationships\":[]}}";

    @Mock
    private ModelRepository modelRepository;
    @Mock
    private ModelDiagramRepository modelDiagramRepository;
    @Mock
    private ModelVersionRepository modelVersionRepository;
    @Mock
    private ModelShareRepository shareRepository;
    @Mock
    private WorkspaceTermRepository termRepository;
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
    @Captor
    private ArgumentCaptor<List<WorkspaceTerm>> copiedTermsCaptor;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks
    private TemplateService templateService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(templateService, "properties",
                new AppProperties(null, null, new AppProperties.Template(TEMPLATE_WS), null));
    }

    @Test
    @DisplayName("목록은 설정이 없으면 빈 배열로 은닉한다 — 문서를 조회하지 않는다")
    void listHidesTemplatesWhenNotConfigured() {
        ReflectionTestUtils.setField(templateService, "properties",
                new AppProperties(null, null, null, null));

        assertThat(templateService.list()).isEmpty();

        then(modelRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("목록은 갱신순으로 메타를 내리고, 활성 공유는 문서당 최근 토큰 1건만 조인한다")
    void listJoinsLatestActiveShareToken() {
        Model commerce = templateModel(501L, "쇼핑몰 ERD", "2026-09-16T09:00:00Z");
        Model blog = templateModel(502L, "블로그 ERD", "2026-09-15T09:00:00Z");
        given(modelRepository.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(TEMPLATE_WS))
                .willReturn(List.of(commerce, blog));
        // 최근 발급순: 쇼핑몰 최신 토큰 → 쇼핑몰 옛 토큰 → 종료된 토큰 — 블로그는 링크 없음
        given(shareRepository.findAllByOrderByCreatedAtDescIdDesc()).willReturn(List.of(
                share(501L, "tokA2", null, null, 11L),
                share(501L, "tokA1", null, null, 10L),
                share(501L, "tokDead", PAST, PAST, 9L)));

        List<TemplateSummaryResponse> responses = templateService.list();

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).modelId()).isEqualTo("501");
        assertThat(responses.get(0).name()).isEqualTo("쇼핑몰 ERD");
        assertThat(responses.get(0).databaseType()).isEqualTo("postgresql");
        assertThat(responses.get(0).tableCount()).isEqualTo(3);
        assertThat(responses.get(0).relationshipCount()).isEqualTo(1);
        assertThat(responses.get(0).shareToken()).isEqualTo("tokA2");
        assertThat(responses.get(1).modelId()).isEqualTo("502");
        assertThat(responses.get(1).shareToken()).isNull();
    }

    @Test
    @DisplayName("복제는 문서·main 다이어그램·v0 스냅샷을 저장하고 pruner·감사까지 SQL 가져오기 골격을 따른다")
    void clonePersistsModelDiagramSnapshotAndAudit() {
        Model source = templateModel(501L, "쇼핑몰 ERD", "2026-09-16T09:00:00Z");
        given(modelRepository.findByIdAndWorkspaceId(501L, TEMPLATE_WS)).willReturn(Optional.of(source));
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new DatabaseType(
                        "postgresql", "PostgreSQL", true)));
        given(modelRepository.existsByWorkspaceIdAndName(TARGET_WS, "내 쇼핑몰 ERD")).willReturn(false);
        given(modelRepository.save(any(Model.class))).willAnswer(inv -> {
            Model model = inv.getArgument(0);
            ReflectionTestUtils.setField(model, "id", 601L);
            return model;
        });

        ModelResponse response = templateService.clone(USER_ID, TARGET_WS,
                new CloneFromTemplateRequest(501L, "내 쇼핑몰 ERD"));

        assertThat(response.modelId()).isEqualTo("601");
        assertThat(response.name()).isEqualTo("내 쇼핑몰 ERD");
        assertThat(response.description()).isEqualTo("설명");
        assertThat(response.databaseType()).isEqualTo("postgresql");
        assertThat(response.content()).isEqualTo(CONTENT);

        ArgumentCaptor<Model> modelCaptor = ArgumentCaptor.forClass(Model.class);
        then(modelRepository).should().save(modelCaptor.capture());
        assertThat(modelCaptor.getValue().getWorkspaceId()).isEqualTo(TARGET_WS);
        assertThat(modelCaptor.getValue().getContent()).isEqualTo(CONTENT);

        ArgumentCaptor<ModelDiagram> diagramCaptor = ArgumentCaptor.forClass(ModelDiagram.class);
        then(modelDiagramRepository).should().save(diagramCaptor.capture());
        assertThat(diagramCaptor.getValue().getName()).isEqualTo("main");
        assertThat(diagramCaptor.getValue().isMain()).isTrue();
        assertThat(diagramCaptor.getValue().getLayoutContent()).isEqualTo(
                "{\"nodes\":[],\"edges\":[],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}");

        ArgumentCaptor<ModelVersion> versionCaptor = ArgumentCaptor.forClass(ModelVersion.class);
        then(modelVersionRepository).should().save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getVersion()).isZero();
        assertThat(versionCaptor.getValue().getContent()).isEqualTo(CONTENT);
        assertThat(versionCaptor.getValue().getChangeSummary())
                .isEqualTo("{\"created\":true,\"source\":\"template\",\"templateId\":501,"
                        + "\"tables\":3,\"relationships\":1}");

        then(modelVersionPruner).should().prune(601L, 0L, USER_ID);
        then(auditRecorder).should().record(eq(USER_ID), eq("MODEL_CREATED_FROM_TEMPLATE"), eq("MODEL"),
                eq("601"), any());
    }

    @Test
    @DisplayName("복제는 이름을 생략하면 원본 이름을 쓴다")
    void cloneFallsBackToSourceName() {
        Model source = templateModel(501L, "쇼핑몰 ERD", "2026-09-16T09:00:00Z");
        given(modelRepository.findByIdAndWorkspaceId(501L, TEMPLATE_WS)).willReturn(Optional.of(source));
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new DatabaseType(
                        "postgresql", "PostgreSQL", true)));
        given(modelRepository.existsByWorkspaceIdAndName(TARGET_WS, "쇼핑몰 ERD")).willReturn(false);
        given(modelRepository.save(any(Model.class))).willAnswer(inv -> {
            Model model = inv.getArgument(0);
            ReflectionTestUtils.setField(model, "id", 601L);
            return model;
        });

        ModelResponse response = templateService.clone(USER_ID, TARGET_WS, new CloneFromTemplateRequest(501L, null));

        assertThat(response.name()).isEqualTo("쇼핑몰 ERD");
    }

    @Test
    @DisplayName("복제는 템플릿 워크스페이스 밖의 문서면 404 TEMPLATE_NOT_FOUND다 — 존재 은닉")
    void cloneRejectsModelOutsideTemplateWorkspace() {
        given(modelRepository.findByIdAndWorkspaceId(999L, TEMPLATE_WS)).willReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.clone(USER_ID, TARGET_WS, new CloneFromTemplateRequest(999L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND);
        then(modelRepository).should(never()).save(any(Model.class));
    }

    @Test
    @DisplayName("복제는 비활성 databaseType의 템플릿이면 400 INVALID_DBMS_TYPE이다")
    void cloneRejectsInactiveDatabaseType() {
        Model source = templateModel(501L, "쇼핑몰 ERD", "2026-09-16T09:00:00Z");
        given(modelRepository.findByIdAndWorkspaceId(501L, TEMPLATE_WS)).willReturn(Optional.of(source));
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql")).willReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.clone(USER_ID, TARGET_WS, new CloneFromTemplateRequest(501L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_DBMS_TYPE);
    }

    @Test
    @DisplayName("복제는 대상 워크스페이스 내 이름 중복이면 409 DUPLICATED_NAME이다")
    void cloneRejectsDuplicatedName() {
        Model source = templateModel(501L, "쇼핑몰 ERD", "2026-09-16T09:00:00Z");
        given(modelRepository.findByIdAndWorkspaceId(501L, TEMPLATE_WS)).willReturn(Optional.of(source));
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new DatabaseType(
                        "postgresql", "PostgreSQL", true)));
        given(modelRepository.existsByWorkspaceIdAndName(TARGET_WS, "쇼핑몰 ERD")).willReturn(true);

        assertThatThrownBy(() -> templateService.clone(USER_ID, TARGET_WS, new CloneFromTemplateRequest(501L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATED_NAME);
        then(modelRepository).should(never()).save(any(Model.class));
    }

    @Test
    @DisplayName("복제는 Editor 권한 검사를 먼저 통과시킨다")
    void cloneChecksEditorRoleFirst() {
        given(modelRepository.findByIdAndWorkspaceId(501L, TEMPLATE_WS)).willReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.clone(USER_ID, TARGET_WS, new CloneFromTemplateRequest(501L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND);

        then(roleChecker).should().requireEditor(USER_ID, TARGET_WS);
        then(auditRecorder).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("복제는 문서에 쓰인 물리명의 템플릿 사전 용어만 동반한다 — 대상 기존 용어는 덮어쓰지 않는다")
    void cloneCopiesTemplateTermsMatchingContentPhysicalNames() {
        Model source = templateModel(501L, "쇼핑몰 ERD", "2026-09-16T09:00:00Z", TERM_CONTENT);
        given(modelRepository.findByIdAndWorkspaceId(501L, TEMPLATE_WS)).willReturn(Optional.of(source));
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new DatabaseType("postgresql", "PostgreSQL", true)));
        given(modelRepository.existsByWorkspaceIdAndName(TARGET_WS, "내 쇼핑몰 ERD")).willReturn(false);
        given(modelRepository.save(any(Model.class))).willAnswer(inv -> {
            Model model = inv.getArgument(0);
            ReflectionTestUtils.setField(model, "id", 601L);
            return model;
        });
        // 템플릿 사전: 문서에 있는 member_id(타입 맵 포함)·email, 없는 isbn — 대상에는 email이 이미 있다
        given(termRepository.findByWorkspaceIdOrderByTermAsc(TEMPLATE_WS)).willReturn(List.of(
                new WorkspaceTerm(TEMPLATE_WS, "member_id", "회원ID",
                        "{\"mysql\":\"BIGINT\",\"postgresql\":\"BIGINT\"}", 2L),
                new WorkspaceTerm(TEMPLATE_WS, "email", "이메일", null, 2L),
                new WorkspaceTerm(TEMPLATE_WS, "isbn", "ISBN", null, 2L)));
        given(termRepository.findByWorkspaceIdOrderByTermAsc(TARGET_WS))
                .willReturn(List.of(new WorkspaceTerm(TARGET_WS, "email", "우리 워크스페이스 이메일", null, 7L)));

        templateService.clone(USER_ID, TARGET_WS, new CloneFromTemplateRequest(501L, "내 쇼핑몰 ERD"));

        // 동반 = 문서 물리명 일치(member_id·email) ∩ 대상 없음(email 제외) = member_id 1건
        then(termRepository).should().saveAll(copiedTermsCaptor.capture());
        assertThat(copiedTermsCaptor.getValue()).hasSize(1);
        WorkspaceTerm copied = copiedTermsCaptor.getValue().get(0);
        assertThat(copied.getWorkspaceId()).isEqualTo(TARGET_WS);
        assertThat(copied.getTerm()).isEqualTo("member_id");
        assertThat(copied.getLabel()).isEqualTo("회원ID");
        assertThat(copied.getTermTypes()).isEqualTo("{\"mysql\":\"BIGINT\",\"postgresql\":\"BIGINT\"}");
        assertThat(copied.getCreatedBy()).isEqualTo(USER_ID); // 복제자 명의 — 일반 등록과 같은 형태
    }

    @Test
    @DisplayName("복제는 content를 파싱할 수 없으면 사전 동반을 건너뛴다 — 복제 자체는 성공")
    void cloneSkipsTermCopyForUnparseableContent() {
        Model source = templateModel(501L, "쇼핑몰 ERD", "2026-09-16T09:00:00Z", "{not-json");
        given(modelRepository.findByIdAndWorkspaceId(501L, TEMPLATE_WS)).willReturn(Optional.of(source));
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new DatabaseType("postgresql", "PostgreSQL", true)));
        given(modelRepository.existsByWorkspaceIdAndName(TARGET_WS, "쇼핑몰 ERD")).willReturn(false);
        given(modelRepository.save(any(Model.class))).willAnswer(inv -> {
            Model model = inv.getArgument(0);
            ReflectionTestUtils.setField(model, "id", 601L);
            return model;
        });

        ModelResponse response = templateService.clone(USER_ID, TARGET_WS, new CloneFromTemplateRequest(501L, null));

        assertThat(response.modelId()).isEqualTo("601");
        then(termRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("복제는 대상 사전이 상한(1,000)에 가득 차 있으면 용어를 더하지 않는다")
    void cloneStopsTermCopyAtWorkspaceLimit() {
        Model source = templateModel(501L, "쇼핑몰 ERD", "2026-09-16T09:00:00Z", TERM_CONTENT);
        given(modelRepository.findByIdAndWorkspaceId(501L, TEMPLATE_WS)).willReturn(Optional.of(source));
        given(databaseTypeRepository.findByCodeAndIsActiveTrue("postgresql"))
                .willReturn(Optional.of(new DatabaseType("postgresql", "PostgreSQL", true)));
        given(modelRepository.existsByWorkspaceIdAndName(TARGET_WS, "쇼핑몰 ERD")).willReturn(false);
        given(modelRepository.save(any(Model.class))).willAnswer(inv -> {
            Model model = inv.getArgument(0);
            ReflectionTestUtils.setField(model, "id", 601L);
            return model;
        });
        given(termRepository.findByWorkspaceIdOrderByTermAsc(TARGET_WS)).willReturn(
                IntStream.rangeClosed(1, 1000) // 상한만큼 이미 있다
                        .mapToObj(i -> new WorkspaceTerm(TARGET_WS, "term" + i, "용어" + i, null, 7L))
                        .toList());

        templateService.clone(USER_ID, TARGET_WS, new CloneFromTemplateRequest(501L, null));

        then(termRepository).should(never()).saveAll(anyList());
    }

    private static Model templateModel(long id, String name, String updatedAt) {
        return templateModel(id, name, updatedAt, CONTENT);
    }

    private static Model templateModel(long id, String name, String updatedAt, String content) {
        Model model = new Model(TEMPLATE_WS, name, "설명", "postgresql", content, USER_ID);
        ReflectionTestUtils.setField(model, "id", id);
        ReflectionTestUtils.setField(model, "updatedAt", Instant.parse(updatedAt));
        return model;
    }

    private static ModelShare share(long modelId, String token, Instant startsAt, Instant endsAt, long id) {
        ModelShare share = new ModelShare(modelId, token, startsAt, endsAt, USER_ID);
        ReflectionTestUtils.setField(share, "id", id);
        return share;
    }
}
