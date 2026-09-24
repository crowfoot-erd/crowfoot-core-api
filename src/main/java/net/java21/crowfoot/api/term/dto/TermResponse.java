package net.java21.crowfoot.api.term.dto;

import java.time.Instant;

/**
 * 용어 사전 항목 응답 (08-core/01-workspace.md Section 4) — 추론은 term→label만 쓰고
 * type은 컬럼 생성 제안 등 부가 정보로 내려준다(선택 값이라 null 가능).
 */
public record TermResponse(
        String termId,
        String workspaceId,
        String term,
        String label,
        String type,
        Instant updatedAt
) {
}
