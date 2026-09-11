package net.java21.crowfoot.api.workspace.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 멤버십 역할 변경 요청 (08-core/03-membership.md Section 1.4) — EDITOR/COMMENTER/VIEWER만 지정 가능.
 */
public record ChangeRoleRequest(
        @NotBlank String role
) {
}
