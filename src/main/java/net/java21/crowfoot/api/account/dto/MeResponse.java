package net.java21.crowfoot.api.account.dto;

import java.time.Instant;
import java.util.List;

/**
 * 내 프로필 응답 (08-core/05-account.md Section 1.1) — 화면 표시용 최신 프로필.
 */
public record MeResponse(
        String userId,
        String email,
        String name,
        List<String> providers,
        boolean admin,
        Instant createdAt
) {
}
