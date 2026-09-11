package net.java21.crowfoot.api.team.controller;

import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.repository.UserQueryRepository.UserCandidateResponse;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.team.dto.AddTeamMemberRequest;
import net.java21.crowfoot.api.team.dto.CreateTeamRequest;
import net.java21.crowfoot.api.team.dto.CreateTeamResponse;
import net.java21.crowfoot.api.team.dto.TeamDetailResponse;
import net.java21.crowfoot.api.team.dto.TeamMemberResponse;
import net.java21.crowfoot.api.team.dto.TeamResponse;
import net.java21.crowfoot.api.team.service.TeamService;
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
 * 팀 API (08-core/04-team.md Section 1) — 구현 경로 /core/**
 * (Gateway URL Rewrite 후 — 외부 계약은 /api/v1/core/*).
 */
@RestController
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    /** 팀 목록 — ownedOnly=true면 내가 Owner인 팀만 */
    @GetMapping("/core/teams")
    public ListApiResponse<TeamResponse> list(
            @RequestParam(name = "ownedOnly", required = false, defaultValue = "false") boolean ownedOnly) {
        return teamService.list(CurrentUserHolder.get().userId(), ownedOnly);
    }

    @PostMapping("/core/teams")
    public ResponseEntity<ApiResponse<CreateTeamResponse>> create(
            @Valid @RequestBody CreateTeamRequest request) {
        CreateTeamResponse response = teamService.create(CurrentUserHolder.get().userId(), request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/teams/" + response.teamId()))
                .body(ApiResponse.success(response));
    }

    @GetMapping("/core/teams/{team-id}")
    public ApiResponse<TeamDetailResponse> get(@PathVariable("team-id") long teamId) {
        return ApiResponse.success(teamService.get(CurrentUserHolder.get().userId(), teamId));
    }

    /** 팀 정보 변경(이름·설명) — Owner만 */
    @PatchMapping("/core/teams/{team-id}")
    public ApiResponse<TeamDetailResponse> patch(
            @PathVariable("team-id") long teamId,
            @RequestBody JsonNode body) {
        return ApiResponse.success(teamService.patch(CurrentUserHolder.get().userId(), teamId, body));
    }

    /** 팀 해체 — 본문 없음 */
    @DeleteMapping("/core/teams/{team-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dissolve(@PathVariable("team-id") long teamId) {
        teamService.dissolve(CurrentUserHolder.get().userId(), teamId);
    }

    @GetMapping("/core/teams/{team-id}/members")
    public ListApiResponse<TeamMemberResponse> members(@PathVariable("team-id") long teamId) {
        return teamService.members(CurrentUserHolder.get().userId(), teamId);
    }

    /** 멤버 추가 — 201 header-only (바디 없음) */
    @PostMapping("/core/teams/{team-id}/members")
    public ResponseEntity<Void> addMember(
            @PathVariable("team-id") long teamId,
            @Valid @RequestBody AddTeamMemberRequest request) {
        long addedUserId = teamService.addMember(CurrentUserHolder.get().userId(), teamId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/teams/" + teamId + "/members/" + addedUserId))
                .build();
    }

    /** 멤버 제외 — 본문 없음 */
    @DeleteMapping("/core/teams/{team-id}/members/{user-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable("team-id") long teamId, @PathVariable("user-id") long userId) {
        teamService.removeMember(CurrentUserHolder.get().userId(), teamId, userId);
    }

    /** 팀 멤버 후보 검색 — keyword(2자 이상)·limit(기본 10, 최대 20) */
    @GetMapping("/core/teams/{team-id}/member-candidates")
    public ListApiResponse<UserCandidateResponse> memberCandidates(
            @PathVariable("team-id") long teamId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        return teamService.memberCandidates(CurrentUserHolder.get().userId(), teamId, keyword, limit);
    }
}
