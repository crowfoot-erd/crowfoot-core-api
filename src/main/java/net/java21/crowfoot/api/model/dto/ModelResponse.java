package net.java21.crowfoot.api.model.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/**
 * 모델 응답 — 생성·상세 (08-core/02-model.md Section 1 "모델 응답 필드" 전체, content 포함).
 * 목록은 content를 제외한 {@link ModelSummaryResponse}를 쓴다.
 */
public record ModelResponse(
        String modelId,
        String workspaceId,
        String name,
        String description,
        String databaseType,
        String content,
        int version,
        UserRefResponse createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
