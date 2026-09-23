package net.java21.crowfoot.api.term.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.term.dto.TermResponse;
import net.java21.crowfoot.api.term.dto.UpsertTermRequest;
import net.java21.crowfoot.api.term.service.TermService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 워크스페이스 용어 사전 API (08-core/01-workspace.md Section 4) — 구현 경로 /core/** (Gateway URL Rewrite 후).
 * 에디터 논리명 자동 추론의 커스텀 사전 — 목록·upsert·삭제.
 */
@RestController
@RequiredArgsConstructor
public class TermController {

    private final TermService termService;

    /** 용어 목록 — 멤버 전체, 페이징 메타 없는 목록 */
    @GetMapping("/core/workspaces/{workspace-id}/terms")
    public ListApiResponse<TermResponse> list(
            @PathVariable("workspace-id") long workspaceId) {
        return ListApiResponse.of(termService.list(CurrentUserHolder.get().userId(), workspaceId));
    }

    /** 용어 등록·수정(upsert) — Editor 이상. 자연키라 신규·수정 구분 없이 항상 200 */
    @PostMapping("/core/workspaces/{workspace-id}/terms")
    public ApiResponse<TermResponse> upsert(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody UpsertTermRequest request) {
        return ApiResponse.success(
                termService.upsert(CurrentUserHolder.get().userId(), workspaceId, request));
    }

    /** 용어 삭제 — Editor 이상, 본문 없음 */
    @DeleteMapping("/core/workspaces/{workspace-id}/terms/{term-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("term-id") long termId) {
        termService.delete(CurrentUserHolder.get().userId(), workspaceId, termId);
    }
}
