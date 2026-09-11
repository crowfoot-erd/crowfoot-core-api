package net.java21.crowfoot.api.team.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/**
 * 팀 멤버 항목 (08-core/04-team.md Section 1.3) — teamRole은 Owner 판정 파생(OWNER/MEMBER),
 * addedBy는 최초 멤버(Owner 생성 행)면 null.
 */
public record TeamMemberResponse(
        String userId,
        String name,
        String email,
        String teamRole,
        UserRefResponse addedBy,
        Instant addedAt
) {
}
