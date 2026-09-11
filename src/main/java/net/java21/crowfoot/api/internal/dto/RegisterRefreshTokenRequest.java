package net.java21.crowfoot.api.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Refresh 토큰 저장소 등록(발급) 요청 (08-core/05-account.md Section 3.2).
 */
public record RegisterRefreshTokenRequest(
        @NotBlank String userId,
        @NotBlank String jti,
        @NotBlank String sid,
        @NotNull Instant expiresAt,
        String ip,
        String userAgent
) {
}
