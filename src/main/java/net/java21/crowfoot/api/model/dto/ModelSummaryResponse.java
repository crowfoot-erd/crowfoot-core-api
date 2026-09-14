package net.java21.crowfoot.api.model.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/**
 * 모델 요약 응답 — 목록 (08-core/02-model.md Section 1.1 "모델 응답 필드"에서 content 제외).
 */
public record ModelSummaryResponse(
        String modelId,
        String workspaceId,
        String name,
        String description,
        String databaseType,
        int version,
        UserRefResponse createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
