package net.java21.crowfoot.api.account.dto;

/**
 * 관리자 데이터베이스 종류 코드 응답 (08-core/05-account.md Section 2.8) — 활성 여부 포함 전체.
 * AdminProviderResponse와 같은 형태(표시명·활성 토글만 관리 대상).
 */
public record AdminDatabaseTypeResponse(
        String code,
        String displayName,
        boolean isActive
) {
}
