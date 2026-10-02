package net.java21.crowfoot.api.model.edit;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문서 편집 API (08-core/17-model-edit.md Section 3). 호출하는 쪽은 MCP 서버(crowfoot-mcp)다.
 */
@RestController
@RequiredArgsConstructor
public class ModelEditController {

    private final ModelEditService service;

    /** 개요 조회 — Viewer 이상 (3.1) */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/outline")
    public ApiResponse<Map<String, Object>> outline(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId) {
        return ApiResponse.success(service.outline(CurrentUserHolder.get().userId(), workspaceId, modelId));
    }

    /** 요구사항 반영 — Editor 이상 (3.2) */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/requirements/apply")
    public ApiResponse<ModelEditService.EditResult> applyRequirements(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @RequestBody EditRequests.RequirementsApply request) {
        return ApiResponse.success(service.applyRequirements(CurrentUserHolder.get().userId(), workspaceId, modelId, request));
    }

    /** 스키마 반영 — Editor 이상 (3.3) */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/schema/apply")
    public ApiResponse<ModelEditService.EditResult> applySchema(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @RequestBody EditRequests.SchemaApply request) {
        return ApiResponse.success(service.applySchema(CurrentUserHolder.get().userId(), workspaceId, modelId, request));
    }

    /** 삭제 — Editor 이상 (3.4) */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/schema/remove")
    public ApiResponse<ModelEditService.EditResult> remove(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @RequestBody EditRequests.SchemaRemove request) {
        return ApiResponse.success(service.remove(CurrentUserHolder.get().userId(), workspaceId, modelId, request));
    }
}
