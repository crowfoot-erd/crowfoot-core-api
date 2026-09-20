package net.java21.crowfoot.api.model.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/**
 * 버전 기록 목록 행 (08-core/02-model.md Section 1.11) — content(최대 5MB)를 제외한 요약.
 * 기존 ModelVersionResponse(1.9 협업 폴링 — 단일 version)와 이름이 겹치지 않게 Entry/Detail로 구분한다.
 */
public record ModelVersionEntryResponse(
        int version,
        String changeSummary,
        String memo,
        UserRefResponse createdBy,
        Instant createdAt
) {
}
