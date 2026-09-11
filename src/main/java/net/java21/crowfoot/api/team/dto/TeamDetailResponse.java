package net.java21.crowfoot.api.team.dto;

import java.time.Instant;

/**
 * 팀 상세 — 목록 항목 + myRole(OWNER/MEMBER 파생).
 */
public record TeamDetailResponse(
        String teamId,
        String name,
        String description,
        boolean isOwner,
        String ownerUserId,
        int memberCount,
        String myRole,
        Instant createdAt
) {
}
