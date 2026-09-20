package net.java21.crowfoot.api.model.controller;

import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.model.dto.CreateModelRequest;
import net.java21.crowfoot.api.model.dto.DeployModelRequest;
import net.java21.crowfoot.api.model.dto.MigrationDdlResponse;
import net.java21.crowfoot.api.model.dto.ModelDeployResponse;
import net.java21.crowfoot.api.model.dto.ModelDdlResponse;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.ModelSummaryResponse;
import net.java21.crowfoot.api.model.dto.ModelVersionResponse;
import net.java21.crowfoot.api.model.dto.SaveContentRequest;
import net.java21.crowfoot.api.model.dto.SaveContentResponse;
import net.java21.crowfoot.api.model.service.DdlService;
import net.java21.crowfoot.api.model.service.MigrationDdlService;
import net.java21.crowfoot.api.model.service.DeployService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * ERD 문서 API (08-core/02-model.md Section 1) — 구현 경로 /core/**
 * (Gateway URL Rewrite 후 — 외부 계약은 /api/v1/core/*). 목록·상세·생성·메타 변경·삭제·content 저장.
 */
@RestController
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;
    private final DdlService ddlService;
    private final DeployService deployService;
    private final MigrationDdlService migrationDdlService;

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

    /** 버전 경량 조회(협업 폴링) — Viewer 이상, content 없이 version만 (1.9) */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/version")
    public ApiResponse<ModelVersionResponse> version(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId) {
        return ApiResponse.success(modelService.version(CurrentUserHolder.get().userId(), workspaceId, modelId));
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

    /** 문서 본체 저장(낙관적 잠금) — Editor 이상, 갱신된 버전·일시를 응답 (1.5) */
    @PutMapping("/core/workspaces/{workspace-id}/models/{model-id}/content")
    public ApiResponse<SaveContentResponse> saveContent(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @Valid @RequestBody SaveContentRequest request) {
        return ApiResponse.success(
                modelService.saveContent(CurrentUserHolder.get().userId(), workspaceId, modelId, request));
    }

    /** DDL 스크립트 생성 — Viewer 이상, 마지막 저장 본문 기준 (1.7) */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/ddl")
    public ApiResponse<ModelDdlResponse> ddl(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId) {
        return ApiResponse.success(ddlService.generate(CurrentUserHolder.get().userId(), workspaceId, modelId));
    }

    /** 포워드 엔지니어링 배포 — Editor 이상, DDL을 커넥션 DB에 문장별 실행 (1.8) */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/deploy")
    public ApiResponse<ModelDeployResponse> deploy(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @Valid @RequestBody DeployModelRequest request) {
        return ApiResponse.success(deployService.deploy(CurrentUserHolder.get().userId(), workspaceId, modelId,
                Long.parseLong(request.connectionId())));
    }

    /** 실제 DB→문서 마이그레이션 DDL 생성(생성만 — 실행 미제공) — Editor 이상, 커넥션 스키마 조회 기반 (1.7.1) */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/connections/{connection-id}/migration")
    public ApiResponse<MigrationDdlResponse> connectionMigration(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("connection-id") long connectionId) {
        return ApiResponse.success(migrationDdlService.generateConnectionMigration(
                CurrentUserHolder.get().userId(), workspaceId, modelId, connectionId));
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
