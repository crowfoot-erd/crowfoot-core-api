package net.java21.crowfoot.api.term.dto;

import java.time.Instant;

/**
 * 용어 사전 항목 응답 (08-core/01-workspace.md Section 4) — 추론은 term→label만 쓴다.
 */
public record TermResponse(
        String termId,
        String workspaceId,
        String term,
        String label,
        Instant updatedAt
) {
}
