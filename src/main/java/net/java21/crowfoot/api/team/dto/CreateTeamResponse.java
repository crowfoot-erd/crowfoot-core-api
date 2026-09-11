package net.java21.crowfoot.api.team.dto;

/**
 * 팀 생성 응답 — 생성된 팀 식별자. Workspace는 만들지 않는다.
 */
public record CreateTeamResponse(
        String teamId
) {
}
