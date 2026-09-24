package net.java21.crowfoot.api.term.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 시스템 사전 항목 응답 (08-core/01-workspace.md Section 4.5) —
 * labels는 언어→라벨 맵 전체를 그대로 내보낸다(로케일 해석은 클라이언트).
 */
public record SystemTermResponse(
        String termId,
        String term,
        Map<String, String> labels,
        String type,
        Instant updatedAt
) {
}
