package net.java21.crowfoot.api.internal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 사용자 확보(get-or-create) 요청 (08-core/05-account.md Section 3.1) — OAuth2 콜백의 제공자 신원.
 *
 * @param providerUsername 제공자 핸들(GitHub login, 선택) — 기존 사용자 로그인 때 갱신된다
 */
public record GetOrCreateUserRequest(
        @NotBlank String provider,
        @NotBlank String providerUserId,
        String providerUsername,
        String email,
        @NotBlank String name
) {
}
