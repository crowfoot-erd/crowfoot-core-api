package net.java21.crowfoot.api.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 팀 생성 요청 (08-core/04-team.md Section 1.2) — 생성과 동시에 기본 Workspace가 프로비저닝된다.
 */
public record CreateTeamRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description
) {
}
