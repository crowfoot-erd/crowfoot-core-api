package net.java21.crowfoot.api.model.service;

import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
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
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 문서 버전 기록 API (08-core/02-model.md Section 1.11) — 목록·상세·메모 편집·복원.
 * 기록 자체는 저장 경로(ModelService.saveContent)가 남긴다 — 여기선 읽기와 보강(메모)·재적용(복원)만.
 */
@Service
@RequiredArgsConstructor
public class ModelVersionService {

    private static final int MAX_MEMO_LENGTH = 500;

    private final ModelRepository modelRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final ModelVersionQueryRepository modelVersionQueryRepository;
    private final ModelVersionPruner modelVersionPruner;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;

    /** 목록(Viewer 이상 — 1.11) — content 없는 요약 행, 최신순 페이징. keyword는 메모 부분 일치(대소문자 무시) */
    @Transactional(readOnly = true)
    public ListApiResponse<ModelVersionEntryResponse> list(long userId, long workspaceId, long modelId,
                                                           String keyword, Integer page, Integer size) {
        roleChecker.requireMember(userId, workspaceId);
        requireModelInWorkspace(modelId, workspaceId);
        String trimmed = keyword == null ? null : keyword.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = size == null || size < 1 ? 20 : Math.min(size, 100);

        long totalCount = modelVersionQueryRepository.count(modelId, trimmed);
        if (totalCount == 0) {
            return ListApiResponse.paged(List.of(), normalizedPage, normalizedSize, 0);
        }
        List<ModelVersionEntryResponse> responses = modelVersionQueryRepository
                .search(modelId, trimmed, normalizedPage, normalizedSize)
                .stream()
                .map(row -> new ModelVersionEntryResponse((int) row.version(), row.changeSummary(),
                        row.memo(), userRef(row.createdById(), row.createdByName()), row.createdAt()))
                .toList();
        return ListApiResponse.paged(responses, normalizedPage, normalizedSize, totalCount);
    }

    /** 상세(Viewer 이상 — 1.11) — 해당 시점 Canonical 문서 JSON 전문 */
    @Transactional(readOnly = true)
    public ModelVersionDetailResponse detail(long userId, long workspaceId, long modelId, long version) {
        roleChecker.requireMember(userId, workspaceId);
        requireModelInWorkspace(modelId, workspaceId);
        ModelVersion snapshot = modelVersionRepository.findByModelIdAndVersion(modelId, version)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_VERSION_NOT_FOUND));
        return new ModelVersionDetailResponse((int) snapshot.getVersion(), snapshot.getContent(),
                snapshot.getChangeSummary(), snapshot.getMemo(),
                createdByRef(snapshot.getCreatedBy()), snapshot.getCreatedAt());
    }

    /**
     * 메모 편집(Editor 이상 — 1.11) — 자동 요약과 별개인 사용자 자유 메모.
     * PATCH 의미론(메타 변경 1.4와 같다): 필드 생략은 변경 없음, 명시적 null은 삭제.
     * 버전을 올리지 않는 기록 보강이라 낙관적 잠금 대상이 아니다.
     */
    @Transactional
    public ModelVersionEntryResponse updateMemo(long userId, long workspaceId, long modelId,
                                                long version, JsonNode body) {
        roleChecker.requireEditor(userId, workspaceId);
        requireModelInWorkspace(modelId, workspaceId);
        ModelVersion snapshot = modelVersionRepository.findByModelIdAndVersion(modelId, version)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_VERSION_NOT_FOUND));
        if (body.has("memo")) {
            String memo = body.get("memo").isNull() ? null : body.get("memo").asText();
            if (memo != null && (memo.isBlank() || memo.length() > MAX_MEMO_LENGTH)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "메모는 1~500자여야 합니다");
            }
            snapshot.setMemo(memo);
        }
        auditRecorder.record(userId, "MODEL_VERSION_MEMO_UPDATED", "MODEL",
                Long.toString(modelId), Map.of("version", version));
        return new ModelVersionEntryResponse((int) snapshot.getVersion(), snapshot.getChangeSummary(),
                snapshot.getMemo(), createdByRef(snapshot.getCreatedBy()), snapshot.getCreatedAt());
    }

    /**
     * 복원(Editor 이상 — 1.11) — 과거 스냅샷 content를 새 버전으로 저장.
     * 과거 기록은 불변: 조건부 UPDATE(1.5 재사용)로 새 version만 만들고
     * {restoredFrom:N} 요약의 스냅샷을 남긴다 — 되돌린 자체가 새 기록이 된다.
     */
    @Transactional
    public SaveContentResponse restore(long userId, long workspaceId, long modelId, long version,
                                       RestoreModelVersionRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        requireModelInWorkspace(modelId, workspaceId);
        String content = modelVersionRepository.findByModelIdAndVersion(modelId, version)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_VERSION_NOT_FOUND))
                .getContent();

        Instant now = Instant.now();
        int updated = modelRepository.updateContentIfVersionMatches(modelId, workspaceId,
                request.baseVersion(), content, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
        modelVersionRepository.save(new ModelVersion(modelId, model.getVersion(),
                content, "{\"restoredFrom\":" + version + "}", null, userId, now));
        modelVersionPruner.prune(modelId, model.getVersion(), userId);
        auditRecorder.record(userId, "MODEL_RESTORED", "MODEL",
                Long.toString(modelId), Map.of(
                        "restoredFrom", version,
                        "version", model.getVersion()));
        return new SaveContentResponse((int) model.getVersion(), model.getUpdatedAt());
    }

    /** 문서 경계 검사 — content(최대 5MB)를 로드하지 않는 프로젝션으로 존재만 본다(1.9 관례) */
    private void requireModelInWorkspace(long modelId, long workspaceId) {
        if (modelRepository.findVersionRowByIdAndWorkspaceId(modelId, workspaceId).isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND);
        }
    }

    /** 목록 행 기록자 — 이름은 조인 결과(탈퇴 등으로 없으면 이름만 비고 id는 유지) */
    private static UserRefResponse userRef(Long id, String name) {
        return id == null ? null : new UserRefResponse(Long.toString(id), name);
    }

    /** 엔티티 경로 기록자 — toSummary 관례 폴백 조회 */
    private UserRefResponse createdByRef(Long createdBy) {
        if (createdBy == null) {
            return null;
        }
        User user = userRepository.findById(createdBy).orElse(null);
        return user == null ? null : new UserRefResponse(Long.toString(user.getId()), user.getName());
    }
}
