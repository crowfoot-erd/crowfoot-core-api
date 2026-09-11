package net.java21.crowfoot.api.account.dto;

/**
 * 관리자 제공자 코드 응답 (08-core/05-account.md Section 2.5) — 활성 여부 포함 전체.
 */
public record AdminProviderResponse(
        String code,
        String displayName,
        boolean isActive
) {
}
