package net.java21.crowfoot.api.model.sync;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DB → 문서 동기화 API (08-core/02-model.md Section 1.16). 호출하는 쪽은 MCP 서버(crowfoot-mcp)다 —
 * 웹 에디터는 스키마 조회(08-core/06-connection.md Section 3.7)로 받은 본체를 에디터 안에서 병합한다.
 */
@RestController
@RequiredArgsConstructor
public class DocumentSyncController {

    private final DocumentSyncService service;

    /** 동기화 계획 — Editor 이상. 아무것도 바꾸지 않는다 (1.16.1) */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/connections/{connection-id}/sync")
    public ApiResponse<DocumentSyncService.PlanResponse> plan(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("connection-id") long connectionId) {
        return ApiResponse.success(service.plan(CurrentUserHolder.get().userId(), workspaceId, modelId, connectionId));
    }

    /** 동기화 적용 — Editor 이상. 계획 지문이 같을 때만 문서에 반영한다 (1.16.2) */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/connections/{connection-id}/sync/apply")
    public ApiResponse<DocumentSyncService.ApplyResponse> apply(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("connection-id") long connectionId,
            @RequestBody(required = false) DocumentSyncService.ApplyRequest request) {
        return ApiResponse.success(service.apply(CurrentUserHolder.get().userId(), workspaceId, modelId, connectionId,
                request == null ? new DocumentSyncService.ApplyRequest(null, null) : request));
    }
}
