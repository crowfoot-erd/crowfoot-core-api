package net.java21.crowfoot.api.workspace.controller;

import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.workspace.dto.CreateWorkspaceRequest;
import net.java21.crowfoot.api.workspace.dto.WorkspaceResponse;
import net.java21.crowfoot.api.workspace.service.WorkspaceService;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 실체 API (08-core/01-workspace.md) — 구현 경로 /core/workspaces/**.
 */
@RestController
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping("/core/workspaces/{workspace-id}")
    public ApiResponse<WorkspaceResponse> get(@PathVariable("workspace-id") Long workspaceId) {
        return ApiResponse.success(workspaceService.get(CurrentUserHolder.get().userId(), workspaceId));
    }

    /** 생성 성공 — Location은 외부 계약 URI (01-workspace.md Section 1.2) */
    @PostMapping("/core/workspaces")
    public ResponseEntity<ApiResponse<WorkspaceResponse>> create(
            @Valid @RequestBody CreateWorkspaceRequest request) {
        WorkspaceResponse response = workspaceService.create(CurrentUserHolder.get().userId(), request);
        return ResponseEntity
                .created(java.net.URI.create("/api/v1/core/workspaces/" + response.workspaceId()))
                .body(ApiResponse.success(response));
    }

    @PatchMapping("/core/workspaces/{workspace-id}")
    public ApiResponse<WorkspaceResponse> patch(@PathVariable("workspace-id") Long workspaceId,
                                                @RequestBody JsonNode body) {
        return ApiResponse.success(workspaceService.patch(CurrentUserHolder.get().userId(), workspaceId, body));
    }

    @DeleteMapping("/core/workspaces/{workspace-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("workspace-id") Long workspaceId) {
        workspaceService.delete(CurrentUserHolder.get().userId(), workspaceId);
    }
}
