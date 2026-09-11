package net.java21.crowfoot.api.account.dto;

import java.time.Instant;

/**
 * 관리자 활성 세션 응답 (08-core/05-account.md Section 2.3) — sid(lineage) 단위 집계.
 * createdAt=최초 발급, lastUsedAt=마지막 사용(기록 없으면 최초 발급),
 * ip·userAgent=가장 최근 발급 행의 값(미기록은 빈 문자열).
 */
public record AdminSessionResponse(
        String sid,
        Instant createdAt,
        Instant lastUsedAt,
        String ip,
        String userAgent
) {
}
