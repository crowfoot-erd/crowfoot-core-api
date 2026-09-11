package net.java21.crowfoot.api.account.dto;

/**
 * 관리자 역할 코드 응답 (08-core/05-account.md Section 2.6) — level은 조회만(변경 400).
 */
public record AdminRoleResponse(
        String code,
        String displayName,
        int level
) {
}
