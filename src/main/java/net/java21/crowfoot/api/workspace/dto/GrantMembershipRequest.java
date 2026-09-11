package net.java21.crowfoot.api.workspace.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 멤버십 부여 요청 (08-core/03-membership.md Section 1.3) — granteeType 배타 규칙은 CHECK 제약과 동일.
 */
public record GrantMembershipRequest(
        @NotBlank String granteeType,
        String userId,
        String teamId,
        @NotBlank String role
) {
}
