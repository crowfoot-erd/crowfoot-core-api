package net.java21.crowfoot.api.account.dto;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 사용자 응답 (08-core/05-account.md Section 2.1~2.2) — 목록·상세 공통 필드.
 * withdrawnAt는 탈퇴 일시(soft — 값이 있으면 탈퇴한 사용자).
 * identities는 제공자 연동(provider·providerUserId) — 이메일 없는 가입자의 2차 식별 수단.
 */
public record AdminUserResponse(
        String userId,
        String email,
        String name,
        List<AdminIdentityResponse> identities,
        boolean admin,
        Instant withdrawnAt,
        Instant createdAt
) {

    public AdminUserResponse(Long userId, String email, String name, List<AdminIdentityResponse> identities,
                             boolean admin, Instant withdrawnAt, Instant createdAt) {
        this(Long.toString(userId), email, name, identities, admin, withdrawnAt, createdAt);
    }
}
