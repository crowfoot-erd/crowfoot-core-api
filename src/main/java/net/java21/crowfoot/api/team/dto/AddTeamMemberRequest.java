package net.java21.crowfoot.api.team.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 팀 멤버 추가 요청 (08-core/04-team.md Section 1.4) — 개인 초대(팀 초대 링크는 후속).
 */
public record AddTeamMemberRequest(
        @NotBlank String userId
) {
}
