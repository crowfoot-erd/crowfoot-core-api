package net.java21.crowfoot.api.account.dto;

/**
 * 관리자 사용자 응답의 제공자 연동 항목 (08-core/05-account.md Section 2.1~2.2).
 * providerUserId는 제공자 관점 사용자 식별자(GitHub 숫자 ID 등) —
 * 제공자가 이메일을 내려주지 않은 가입자를 구분하는 2차 식별 수단.
 */
public record AdminIdentityResponse(
        String provider,
        String providerUserId
) {
}
