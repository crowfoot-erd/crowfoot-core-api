package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.dto.CreateShareRequest;
import net.java21.crowfoot.api.model.dto.GalleryShareResponse;
import net.java21.crowfoot.api.model.dto.ModelShareResponse;
import net.java21.crowfoot.api.model.dto.PublicShareResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 문서 공유 링크 API (08-core/02-model.md Section 1.10) — 발급·목록·철회(관리, Editor 이상)와
 * 토큰 기반 공개 조회(무인증). 링크는 문서의 읽기 전용 스냅샷이 아니라 최신 본문을 노출한다.
 */
@Service
@RequiredArgsConstructor
public class ShareService {

    private final ModelShareRepository shareRepository;
    private final ModelRepository modelRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ShareTokenGenerator tokenGenerator;

    /** 발급(Editor 이상) — startsAt > endsAt이면 400, 없는 문서면 404 */
    @Transactional
    public ModelShareResponse create(long userId, long workspaceId, long modelId, CreateShareRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        Instant startsAt = request == null ? null : request.startsAt();
        Instant endsAt = request == null ? null : request.endsAt();
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "종료 일시는 시작 일시 이후여야 합니다");
        }
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));

        String token = tokenGenerator.generateUnique(shareRepository::existsByShareToken);
        ModelShare share = shareRepository.save(
                new ModelShare(model.getId(), token, startsAt, endsAt, userId));
        auditRecorder.record(userId, "MODEL_SHARED", "MODEL",
                Long.toString(modelId), Map.of(
                        "shareId", Long.toString(share.getId()),
                        "startsAt", startsAt == null ? "" : startsAt.toString(),
                        "endsAt", endsAt == null ? "" : endsAt.toString()));
        return toResponse(share);
    }

    /** 목록(Editor 이상) — 없는 문서면 404, 최근 발급순 */
    @Transactional(readOnly = true)
    public List<ModelShareResponse> list(long userId, long workspaceId, long modelId) {
        roleChecker.requireEditor(userId, workspaceId);
        if (modelRepository.findByIdAndWorkspaceId(modelId, workspaceId).isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND);
        }
        return shareRepository.findByModelIdOrderByCreatedAtDescIdDesc(modelId)
                .stream()
                .map(ShareService::toResponse)
                .toList();
    }

    /** 철회(Editor 이상) — 링크가 즉시 무효화된다, 없는 문서·링크는 404 */
    @Transactional
    public void revoke(long userId, long workspaceId, long modelId, long shareId) {
        roleChecker.requireEditor(userId, workspaceId);
        if (modelRepository.findByIdAndWorkspaceId(modelId, workspaceId).isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND);
        }
        if (shareRepository.findByIdAndModelId(shareId, modelId).isEmpty()) {
            throw new BusinessException(ErrorCode.SHARE_NOT_FOUND);
        }
        shareRepository.deleteByIdAndModelId(shareId, modelId);
        auditRecorder.record(userId, "MODEL_SHARE_REVOKED", "MODEL",
                Long.toString(modelId), Map.of("shareId", Long.toString(shareId)));
    }

    /**
     * 공개 조회(무인증) — 토큰을 아는 누구나. 시작 전·종료 후면 410 SHARE_INACTIVE로
     * 링크의 죽음을 알린다. 문서가 삭제되었으면(FK CASCADE로 링크도 삭제) 404 SHARE_NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public PublicShareResponse resolve(String token) {
        ModelShare share = shareRepository.findByShareToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        if (!isActive(share, Instant.now())) {
            throw new BusinessException(ErrorCode.SHARE_INACTIVE);
        }
        Model model = modelRepository.findById(share.getModelId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        return new PublicShareResponse(
                model.getName(),
                model.getDescription(),
                model.getDatabaseType(),
                (int) model.getVersion(),
                model.getContent(),
                share.getStartsAt(),
                share.getEndsAt());
    }

    /**
     * 공개 갤러리(무인증, 08-core/02-model.md Section 1.10.5) — 현재 공유 중인 문서의 목록.
     * 활성 링크(기간 내)만, 문서당 최근 발급 링크 1개, 문서 갱신순으로 내려준다.
     * 본문(content, 최대 5MB)은 미포함 — 랜딩 카드는 메타만 보여준다.
     */
    @Transactional(readOnly = true)
    public List<GalleryShareResponse> gallery() {
        Instant now = Instant.now();
        Map<Long, ModelShare> latestByModel = new LinkedHashMap<>();
        for (ModelShare share : shareRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            if (isActive(share, now)) {
                latestByModel.putIfAbsent(share.getModelId(), share); // 최근 발급순이라 선두가 그 문서의 최신 링크
            }
        }
        if (latestByModel.isEmpty()) {
            return List.of();
        }
        Map<Long, Model> models = modelRepository.findAllById(latestByModel.keySet()).stream()
                .collect(Collectors.toMap(Model::getId, Function.identity()));
        return latestByModel.entrySet().stream()
                .filter(entry -> models.containsKey(entry.getKey())) // 방어 — CASCADE 삭제로 사실상 없는 경우
                .map(entry -> {
                    Model model = models.get(entry.getKey());
                    ModelShare share = entry.getValue();
                    return new GalleryShareResponse(
                            share.getShareToken(),
                            model.getName(),
                            model.getDescription(),
                            model.getDatabaseType(),
                            model.getUpdatedAt(),
                            share.getCreatedAt());
                })
                .sorted(Comparator.comparing(GalleryShareResponse::updatedAt).reversed())
                .toList();
    }

    /** 링크 기간 판정 — 시작일 null은 즉시, 종료일 null은 무제한 */
    private static boolean isActive(ModelShare share, Instant now) {
        return (share.getStartsAt() == null || !now.isBefore(share.getStartsAt()))
                && (share.getEndsAt() == null || !now.isAfter(share.getEndsAt()));
    }

    private static ModelShareResponse toResponse(ModelShare share) {
        return new ModelShareResponse(
                Long.toString(share.getId()),
                share.getShareToken(),
                share.getStartsAt(),
                share.getEndsAt(),
                share.getCreatedAt());
    }
}
