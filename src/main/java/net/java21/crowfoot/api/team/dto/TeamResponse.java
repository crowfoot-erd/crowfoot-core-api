package net.java21.crowfoot.api.team.dto;

import java.time.Instant;

/**
 * 팀 목록 항목 (08-core/04-team.md Section 1.1) — 소속 팀 전체 또는 내가 Owner인 팀만.
 */
public record TeamResponse(
        String teamId,
        String name,
        String description,
        boolean isOwner,
        String ownerUserId,
        int memberCount,
        Instant createdAt
) {
}
