package net.java21.crowfoot.api.model.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/**
 * 모델 요약 응답 — 목록 (08-core/02-model.md Section 1.1 "모델 응답 필드"에서 content 제외).
 * hasSite는 그 문서에 만든 사이트가 등록돼 있는지다 — 목록 행의 사이트 아이콘 표시(v1.42).
 */
public record ModelSummaryResponse(
        String modelId,
        String workspaceId,
        String name,
        String description,
        String databaseType,
        String sourceConnectionId,
        int version,
        UserRefResponse createdBy,
        Instant createdAt,
        Instant updatedAt,
        boolean hasSite
) {

    /** 사이트 쇼케이스 이전 꼴 — hasSite는 false(08-core/19-site-showcase.md Section 6, v1.42) */
    public ModelSummaryResponse(String modelId, String workspaceId, String name, String description, String databaseType,
                                String sourceConnectionId, int version, UserRefResponse createdBy, Instant createdAt,
                                Instant updatedAt) {
        this(modelId, workspaceId, name, description, databaseType, sourceConnectionId, version, createdBy, createdAt,
                updatedAt, false);
    }
}
