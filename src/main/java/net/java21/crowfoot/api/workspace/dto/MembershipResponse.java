package net.java21.crowfoot.api.workspace.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.dto.UserSummaryResponse;

import java.time.Instant;

/**
 * 멤버십(부여 행) 응답 (08-core/03-membership.md — 멤버십 응답 필드).
 * granteeType에 따라 user·team 중 하나만 채운다.
 */
public record MembershipResponse(
        String membershipId,
        String granteeType,
        UserSummaryResponse user,
        TeamSummaryResponse team,
        String role,
        UserRefResponse grantedBy,
        Instant grantedAt
) {
}
