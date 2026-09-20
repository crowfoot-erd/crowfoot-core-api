package net.java21.crowfoot.api.model.controller;

import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.model.dto.ModelVersionDetailResponse;
import net.java21.crowfoot.api.model.dto.ModelVersionEntryResponse;
import net.java21.crowfoot.api.model.dto.RestoreModelVersionRequest;
import net.java21.crowfoot.api.model.dto.SaveContentResponse;
import net.java21.crowfoot.api.model.service.ModelVersionService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문서 버전 기록 API (08-core/02-model.md Section 1.11) — 구현 경로 /core/**
 * (Gateway URL Rewrite 후 — 외부 계약은 /api/v1/core/*). 목록·상세·메모 편집·복원.
 */
@RestController
@RequiredArgsConstructor
public class ModelVersionController {

    private final ModelVersionService modelVersionService;

    /** 버전 기록 목록(요약 — content 제외, 최신순) — Viewer */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/versions")
    public ListApiResponse<ModelVersionEntryResponse> list(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return modelVersionService.list(CurrentUserHolder.get().userId(), workspaceId, modelId, page, size);
    }

    /** 버전 상세(해당 시점 content 전문) — Viewer, 버전 뷰어가 여는 호출 */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/versions/{version}")
    public ApiResponse<ModelVersionDetailResponse> detail(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("version") long version) {
        return ApiResponse.success(
                modelVersionService.detail(CurrentUserHolder.get().userId(), workspaceId, modelId, version));
    }

    /** 버전 메모 편집(자동 요약과 별개 자유 메모) — Editor, 갱신된 목록 행을 응답 */
    @PatchMapping("/core/workspaces/{workspace-id}/models/{model-id}/versions/{version}/memo")
    public ApiResponse<ModelVersionEntryResponse> updateMemo(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("version") long version,
            @RequestBody JsonNode body) {
        return ApiResponse.success(modelVersionService.updateMemo(
                CurrentUserHolder.get().userId(), workspaceId, modelId, version, body));
    }

    /** 버전 복원(과거 content를 새 버전으로 저장) — Editor, 저장 응답(새 version·일시) 재사용 */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/versions/{version}/restore")
    public ApiResponse<SaveContentResponse> restore(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("version") long version,
            @Valid @RequestBody RestoreModelVersionRequest request) {
        return ApiResponse.success(modelVersionService.restore(
                CurrentUserHolder.get().userId(), workspaceId, modelId, version, request));
    }
}
