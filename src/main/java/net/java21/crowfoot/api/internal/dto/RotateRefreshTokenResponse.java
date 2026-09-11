package net.java21.crowfoot.api.internal.dto;

/**
 * Refresh Rotation 판정 결과 — verdict: ROTATED(정상) / GRACE(유예 내 재사용·멀티탭).
 */
public record RotateRefreshTokenResponse(String verdict, String latestJti) {
}
