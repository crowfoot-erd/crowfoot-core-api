package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.dto.ModelVersionDetailResponse;
import net.java21.crowfoot.api.model.dto.ModelVersionEntryResponse;
import net.java21.crowfoot.api.model.dto.RestoreModelVersionRequest;
import net.java21.crowfoot.api.model.dto.SaveContentResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionQueryRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionQueryRepository.VersionRow;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.ListApiResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 버전 기록 API 단위 테스트 (08-core/02-model.md Section 1.11) —
 * 목록(content 제외 페이징)·상세·메모 편집(PATCH 의미론·500자 가드)·복원(새 버전 저장·409)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ModelVersionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ModelRepository modelRepository;
    @Mock
    private ModelVersionRepository modelVersionRepository;
    @Mock
    private ModelVersionQueryRepository modelVersionQueryRepository;
    @Mock
    private ModelVersionPruner modelVersionPruner;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;

    private ModelVersionService service;

    @BeforeEach
    void setUp() {
        service = new ModelVersionService(modelRepository, modelVersionRepository,
                modelVersionQueryRepository, modelVersionPruner, userRepository, roleChecker, auditRecorder);
    }

    private static JsonNode jsonNode(String json) {
        return MAPPER.readValue(json, JsonNode.class);
    }

    /** 경계 검사 통과용 — 문서 501은 워크스페이스 77에 version 3로 존재 */
    private void stubModelInWorkspace() {
        given(modelRepository.findVersionRowByIdAndWorkspaceId(501L, 77L))
                .willReturn(Collections.singletonList(new Object[] {3L, Instant.parse("2026-09-20T05:00:00Z")}));
    }

    private static ModelVersion snapshot(long version) {
        return new ModelVersion(501L, version, "{\"v\":%d}".formatted(version),
                "{\"items\":[]}", null, 7L, Instant.parse("2026-09-20T05:00:00Z"));
    }

    private static User marco() {
        User user = new User("marco@x.com", "marco", true);
        ReflectionTestUtils.setField(user, "id", 7L);
        return user;
    }

    @Test
    @DisplayName("목록은 content 없는 요약 행을 최신순 페이징 포맷으로 내린다 — 기록자 이름은 조인 결과")
    void listReturnsPagedEntriesWithoutContent() {
        stubModelInWorkspace();
        given(modelVersionQueryRepository.count(501L, null)).willReturn(3L);
        given(modelVersionQueryRepository.search(501L, null, 1, 2)).willReturn(List.of(
                new VersionRow(3L, "{\"layoutOnly\":true}", null, 7L, "marco",
                        Instant.parse("2026-09-20T05:00:00Z")),
                new VersionRow(2L, null, "초안 메모", 7L, "marco",
                        Instant.parse("2026-09-19T05:00:00Z"))));

        ListApiResponse<ModelVersionEntryResponse> response = service.list(7L, 77L, 501L, null, 1, 2);

        verify(roleChecker).requireMember(7L, 77L);
        assertThat(response.totalCount()).isEqualTo(3);
        assertThat(response.responses()).hasSize(2);
        assertThat(response.responses().get(0).version()).isEqualTo(3);
        assertThat(response.responses().get(0).createdBy().name()).isEqualTo("marco");
        assertThat(response.responses().get(1).memo()).isEqualTo("초안 메모");
        // 페이지 정규화 — 미지정은 1·20, 상한 100
        service.list(7L, 77L, 501L, null, null, null);
        verify(modelVersionQueryRepository).search(501L, null, 1, 20);
    }

    @Test
    @DisplayName("목록 keyword는 trim·빈값을 null로 정규화해 리포지토리에 위임한다 — 메모 검색")
    void listNormalizesKeyword() {
        stubModelInWorkspace();
        given(modelVersionQueryRepository.count(501L, "등급")).willReturn(1L);
        given(modelVersionQueryRepository.search(501L, "등급", 1, 20)).willReturn(List.of(
                new VersionRow(1L, null, "등급 컬럼 추가", 7L, "marco",
                        Instant.parse("2026-09-20T05:00:00Z"))));

        service.list(7L, 77L, 501L, "  등급  ", 1, 20);
        verify(modelVersionQueryRepository).search(501L, "등급", 1, 20);

        // 빈·공백 keyword는 전체 목록(null 위임)
        given(modelVersionQueryRepository.count(501L, null)).willReturn(3L);
        service.list(7L, 77L, 501L, "   ", null, null);
        verify(modelVersionQueryRepository).search(501L, null, 1, 20);
    }

    @Test
    @DisplayName("목록도 없는 문서·다른 Workspace 소속은 404 MODEL_NOT_FOUND다")
    void listRejectsUnknownModel() {
        given(modelRepository.findVersionRowByIdAndWorkspaceId(501L, 77L)).willReturn(List.of());

        assertThatThrownBy(() -> service.list(7L, 77L, 501L, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_NOT_FOUND));

        verify(modelVersionQueryRepository, never()).count(anyLong(), anyString());
    }

    @Test
    @DisplayName("상세는 해당 시점 content 전문을 내린다 — 기록자 이름은 폴백 조회")
    void detailReturnsContentOfTheVersion() {
        stubModelInWorkspace();
        ModelVersion snapshot = snapshot(2L);
        given(modelVersionRepository.findByModelIdAndVersion(501L, 2L)).willReturn(Optional.of(snapshot));
        given(userRepository.findById(7L)).willReturn(Optional.of(marco()));

        ModelVersionDetailResponse response = service.detail(7L, 77L, 501L, 2L);

        assertThat(response.version()).isEqualTo(2);
        assertThat(response.content()).isEqualTo("{\"v\":2}");
        assertThat(response.createdBy().name()).isEqualTo("marco");
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-09-20T05:00:00Z"));
    }

    @Test
    @DisplayName("상세는 없는 버전이면 404 MODEL_VERSION_NOT_FOUND다")
    void detailRejectsUnknownVersion() {
        stubModelInWorkspace();
        given(modelVersionRepository.findByModelIdAndVersion(501L, 9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(7L, 77L, 501L, 9L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_VERSION_NOT_FOUND));
    }

    @Test
    @DisplayName("메모 편집은 자유 메모를 남기고 감사를 기록한다 — version은 그대로")
    void updateMemoSetsMemoAndAudits() {
        stubModelInWorkspace();
        ModelVersion snapshot = snapshot(2L);
        given(modelVersionRepository.findByModelIdAndVersion(501L, 2L)).willReturn(Optional.of(snapshot));
        given(userRepository.findById(7L)).willReturn(Optional.of(marco()));

        ModelVersionEntryResponse response = service.updateMemo(7L, 77L, 501L, 2L,
                jsonNode("{\"memo\":\"member 테이블 추가\"}"));

        assertThat(snapshot.getMemo()).isEqualTo("member 테이블 추가");
        assertThat(response.memo()).isEqualTo("member 테이블 추가");
        verify(roleChecker).requireEditor(7L, 77L);
        verify(auditRecorder).record(eq(7L), eq("MODEL_VERSION_MEMO_UPDATED"), eq("MODEL"),
                eq("501"), any());
    }

    @Test
    @DisplayName("메모 명시적 null은 삭제다 — 필드 생략은 변경 없음 (PATCH 의미론)")
    void updateMemoNullDeletesAndOmissionKeeps() {
        stubModelInWorkspace();
        ModelVersion snapshot = snapshot(2L);
        snapshot.setMemo("기존 메모");
        given(modelVersionRepository.findByModelIdAndVersion(501L, 2L)).willReturn(Optional.of(snapshot));

        service.updateMemo(7L, 77L, 501L, 2L, jsonNode("{\"memo\":null}"));
        assertThat(snapshot.getMemo()).isNull();

        snapshot.setMemo("기존 메모");
        service.updateMemo(7L, 77L, 501L, 2L, jsonNode("{}"));
        assertThat(snapshot.getMemo()).isEqualTo("기존 메모"); // 생략 — 변경 없음
    }

    @Test
    @DisplayName("빈 값·501자 초과 메모는 400 INVALID_REQUEST다")
    void updateMemoRejectsBlankOrTooLong() {
        stubModelInWorkspace();
        given(modelVersionRepository.findByModelIdAndVersion(501L, 2L)).willReturn(Optional.of(snapshot(2L)));

        assertThatThrownBy(() -> service.updateMemo(7L, 77L, 501L, 2L, jsonNode("{\"memo\":\" \"}")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.updateMemo(7L, 77L, 501L, 2L,
                jsonNode("{\"memo\":\"" + "x".repeat(501) + "\"}")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("복원은 과거 content를 새 버전으로 저장하고 {restoredFrom} 스냅샷·감사를 남긴다")
    void restoreSavesPastContentAsNewVersion() {
        stubModelInWorkspace();
        String pastContent = "{\"v\":2}";
        given(modelVersionRepository.findByModelIdAndVersion(501L, 2L))
                .willReturn(Optional.of(snapshot(2L)));
        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(3L),
                eq(pastContent), any())).willReturn(1);
        Model model = new Model(77L, "주문 서비스 ERD", null, "postgresql", "{}", 7L);
        ReflectionTestUtils.setField(model, "id", 501L);
        ReflectionTestUtils.setField(model, "version", 4L);
        ReflectionTestUtils.setField(model, "updatedAt", Instant.parse("2026-09-20T06:00:00Z"));
        given(modelRepository.findById(501L)).willReturn(Optional.of(model));

        SaveContentResponse response = service.restore(7L, 77L, 501L, 2L, new RestoreModelVersionRequest(3));

        assertThat(response.version()).isEqualTo(4);
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-09-20T06:00:00Z"));
        // 새 스냅샷 — 과거 content 그대로 + restoredFrom 요약
        ArgumentCaptor<ModelVersion> snapshotCaptor = ArgumentCaptor.forClass(ModelVersion.class);
        verify(modelVersionRepository).save(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getVersion()).isEqualTo(4L);
        assertThat(snapshotCaptor.getValue().getContent()).isEqualTo(pastContent);
        assertThat(snapshotCaptor.getValue().getChangeSummary()).isEqualTo("{\"restoredFrom\":2}");
        assertThat(snapshotCaptor.getValue().getMemo()).isNull();
        // 복원도 보존 정책(1.11.6) 정리를 같은 트랜잭션에서 수행한다
        verify(modelVersionPruner).prune(501L, 4L, 7L);
        verify(auditRecorder).record(eq(7L), eq("MODEL_RESTORED"), eq("MODEL"), eq("501"), any());
    }

    @Test
    @DisplayName("복원도 버전 불일치면 409 VERSION_CONFLICT다 — 스냅샷·감사 없음")
    void restoreRejectsVersionMismatch() {
        stubModelInWorkspace();
        given(modelVersionRepository.findByModelIdAndVersion(501L, 2L))
                .willReturn(Optional.of(snapshot(2L)));
        given(modelRepository.updateContentIfVersionMatches(eq(501L), eq(77L), eq(2L), anyString(), any()))
                .willReturn(0);

        assertThatThrownBy(() -> service.restore(7L, 77L, 501L, 2L, new RestoreModelVersionRequest(2)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));

        verify(modelVersionRepository, never()).save(any());
        verify(auditRecorder, never()).record(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("복원은 없는 버전이면 404 MODEL_VERSION_NOT_FOUND다 — 갱신 실행 없음")
    void restoreRejectsUnknownVersion() {
        stubModelInWorkspace();
        given(modelVersionRepository.findByModelIdAndVersion(501L, 9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(7L, 77L, 501L, 9L, new RestoreModelVersionRequest(3)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_VERSION_NOT_FOUND));

        verify(modelRepository, never()).updateContentIfVersionMatches(anyLong(), anyLong(), anyLong(),
                anyString(), any());
    }
}
