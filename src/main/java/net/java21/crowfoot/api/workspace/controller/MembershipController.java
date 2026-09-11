package net.java21.crowfoot.api.workspace.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.repository.UserQueryRepository.UserCandidateResponse;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.workspace.dto.ChangeRoleRequest;
import net.java21.crowfoot.api.workspace.dto.GrantMembershipRequest;
import net.java21.crowfoot.api.workspace.dto.MembershipIdResponse;
import net.java21.crowfoot.api.workspace.dto.MembershipResponse;
import net.java21.crowfoot.api.workspace.dto.MyWorkspaceResponse;
import net.java21.crowfoot.api.workspace.service.MembershipService;
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
 * 멤버십 API (08-core/03-membership.md Section 1) — 구현 경로 /core/**
 * (Gateway URL Rewrite 후 — 외부 계약은 /api/v1/core/*).
 */
@RestController
@RequiredArgsConstructor
public class MembershipController {

    private final MembershipService membershipService;

    /** 내 워크스페이스 목록 — 페이징 없음 */
    @GetMapping("/core/accounts/me/workspaces")
    public ListApiResponse<MyWorkspaceResponse> myWorkspaces() {
        return membershipService.myWorkspaces(CurrentUserHolder.get().userId());
    }

    @GetMapping("/core/workspaces/{workspace-id}/memberships")
    public ListApiResponse<MembershipResponse> list(@PathVariable("workspace-id") long workspaceId) {
        return membershipService.list(CurrentUserHolder.get().userId(), workspaceId);
    }

    @PostMapping("/core/workspaces/{workspace-id}/memberships")
    public ResponseEntity<ApiResponse<MembershipIdResponse>> grant(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody GrantMembershipRequest request) {
        MembershipIdResponse response =
                membershipService.grant(CurrentUserHolder.get().userId(), workspaceId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId
                        + "/memberships/" + response.membershipId()))
                .body(ApiResponse.success(response));
    }

    @PatchMapping("/core/workspaces/{workspace-id}/memberships/{membership-id}")
    public ApiResponse<MembershipResponse> changeRole(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("membership-id") long membershipId,
            @Valid @RequestBody ChangeRoleRequest request) {
        return ApiResponse.success(
                membershipService.changeRole(CurrentUserHolder.get().userId(), workspaceId, membershipId, request));
    }

    /** 멤버십 회수 — 본문 없음 */
    @DeleteMapping("/core/workspaces/{workspace-id}/memberships/{membership-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable("workspace-id") long workspaceId,
                       @PathVariable("membership-id") long membershipId) {
        membershipService.revoke(CurrentUserHolder.get().userId(), workspaceId, membershipId);
    }

    /** 멤버 부여 후보 검색 — keyword(2자 이상)·limit(기본 10, 최대 20) */
    @GetMapping("/core/workspaces/{workspace-id}/membership-candidates")
    public ListApiResponse<UserCandidateResponse> candidates(
            @PathVariable("workspace-id") long workspaceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        return membershipService.candidates(CurrentUserHolder.get().userId(), workspaceId, keyword, limit);
    }
}
