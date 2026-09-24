package net.java21.crowfoot.api.term.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 시스템 사전 항목 응답 (08-core/01-workspace.md Section 4.5) —
 * labels는 언어→라벨 맵 전체를, types는 DBMS 종류별 데이터 타입 맵 전체를 그대로 내보낸다
 * (로케일·DBMS 해석은 클라이언트). 둘 다 맵이라 null 가능 여부만 서버가 정한다.
 */
public record SystemTermResponse(
        String termId,
        String term,
        Map<String, String> labels,
        Map<String, String> types,
        Instant updatedAt
) {
}
