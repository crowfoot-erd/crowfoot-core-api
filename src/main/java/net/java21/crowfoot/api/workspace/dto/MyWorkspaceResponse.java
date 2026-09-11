package net.java21.crowfoot.api.workspace.dto;

/**
 * 내 워크스페이스 목록 항목 (08-core/03-membership.md Section 1.1) — 멤버십 × 실체 1회 조합.
 * myRole은 유효 역할(개인 + 소속 팀 부여의 max).
 */
public record MyWorkspaceResponse(
        String workspaceId,
        String name,
        String description,
        boolean isDefault,
        String myRole,
        int memberCount
) {
}
