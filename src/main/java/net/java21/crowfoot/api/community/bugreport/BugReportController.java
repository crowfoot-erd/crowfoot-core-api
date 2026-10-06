package net.java21.crowfoot.api.community.bugreport;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** MCP 버그 신고 — 워크스페이스 멤버가 "제안 및 신고" 게시판에 글을 올린다 (08-core/08-community.md Section 3.12) */
@RestController
@RequiredArgsConstructor
public class BugReportController {

    private final BugReportService bugReportService;

    @PostMapping("/core/workspaces/{workspace-id}/bug-reports")
    public ResponseEntity<ApiResponse<BugReportResponse>> report(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody BugReportRequest request) {
        BugReportResponse response = bugReportService.report(CurrentUserHolder.get().userId(), workspaceId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/community/posts/" + response.postId()))
                .body(ApiResponse.success(response));
    }
}
