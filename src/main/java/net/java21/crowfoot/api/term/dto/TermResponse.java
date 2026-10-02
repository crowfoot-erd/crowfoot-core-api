package net.java21.crowfoot.api.term.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 용어 사전 항목 응답 (08-core/01-workspace.md Section 4) — 추론은 term→label만 쓰고
 * types는 DBMS 종류별 데이터 타입 맵을 부가 정보로 내려준다(선택 값이라 null 가능).
 */
public record TermResponse(
        String termId,
        String workspaceId,
        String term,
        String label,
        Map<String, String> types,
        /** 가리키는 도메인 타입 id(BIGINT 문자열) — 없으면 null (Section 4.6) */
        String domainTypeId,
        Instant updatedAt
) {
}
