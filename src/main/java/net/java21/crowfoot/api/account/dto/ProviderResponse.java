package net.java21.crowfoot.api.account.dto;

/**
 * 활성 제공자 응답 (08-core/05-account.md Section 1.2) — 코드·표시명만 노출.
 */
public record ProviderResponse(String code, String displayName) {
}
