package net.java21.crowfoot.api.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Refresh Rotation 요청 (08-core/05-account.md Section 3.3) — 3-조건 판정(유예 30초·재사용 감지) 대상.
 */
public record RotateRefreshTokenRequest(
        @NotBlank String userId,
        @NotBlank String currentJti,
        @NotBlank String newJti,
        @NotNull Instant newExpiresAt
) {
}
