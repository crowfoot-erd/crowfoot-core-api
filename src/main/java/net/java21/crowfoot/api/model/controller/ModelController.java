package net.java21.crowfoot.api.model.controller;

import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.model.dto.CreateModelRequest;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.ModelSummaryResponse;
import net.java21.crowfoot.api.model.service.ModelService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * ERD 문서 API (08-core/02-model.md Section 1) — 구현 경로 /core/**
 * (Gateway URL Rewrite 후 — 외부 계약은 /api/v1/core/*). 목록·상세·생성·메타 변경·삭제, content 저장은 에디터 단계.
 */
@RestController
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    /** 모델 목록(요약 — content 제외) — Viewer */
    @GetMapping("/core/workspaces/{workspace-id}/models")
    public ListApiResponse<ModelSummaryResponse> list(
            @PathVariable("workspace-id") long workspaceId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return modelService.list(CurrentUserHolder.get().userId(), workspaceId, keyword, page, size);
    }

    /** 모델 상세(content 포함) — Viewer, 에디터가 문서를 여는 호출(1.3) */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}")
    public ApiResponse<ModelResponse> detail(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId) {
        return ApiResponse.success(modelService.detail(CurrentUserHolder.get().userId(), workspaceId, modelId));
    }

    /** 모델 생성(main 다이어그램 자동 생성) — Editor */
    @PostMapping("/core/workspaces/{workspace-id}/models")
    public ResponseEntity<ApiResponse<ModelResponse>> create(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody CreateModelRequest request) {
        ModelResponse response = modelService.create(CurrentUserHolder.get().userId(), workspaceId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId + "/models/" + response.modelId()))
                .body(ApiResponse.success(response));
    }

    /** 모델 메타 변경(이름·설명) — Editor 이상, 갱신된 메타(content 제외)를 응답 */
    @PatchMapping("/core/workspaces/{workspace-id}/models/{model-id}")
    public ApiResponse<ModelSummaryResponse> patch(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @RequestBody JsonNode body) {
        return ApiResponse.success(modelService.patch(CurrentUserHolder.get().userId(), workspaceId, modelId, body));
    }

    /** 모델 삭제 — Owner 전용, 본문 없음 */
    @DeleteMapping("/core/workspaces/{workspace-id}/models/{model-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId) {
        modelService.delete(CurrentUserHolder.get().userId(), workspaceId, modelId);
    }
}
