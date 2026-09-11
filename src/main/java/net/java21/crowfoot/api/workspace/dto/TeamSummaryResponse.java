package net.java21.crowfoot.api.workspace.dto;

/**
 * 팀 부여(granteeType=TEAM) 요약 — { teamId, name, memberCount }.
 */
public record TeamSummaryResponse(String teamId, String name, int memberCount) {
}
