package net.java21.crowfoot.api.model.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/** 버전 기록 상세 (08-core/02-model.md Section 1.11) — 해당 시점 Canonical 문서 JSON 전문 포함. */
public record ModelVersionDetailResponse(
        int version,
        String content,
        String changeSummary,
        String memo,
        UserRefResponse createdBy,
        Instant createdAt
) {
}
