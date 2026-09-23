package net.java21.crowfoot.api.model.sqlimport;

import net.java21.crowfoot.api.model.dto.ModelResponse;

import java.util.List;

/**
 * SQL Import 생성 결과 (08-core/02-model.md Section 1.12) — 리버스 엔지니어링 응답과 같은 뼈대.
 *
 * @param model             생성·저장된 신규 문서 — sourceConnectionId가 없어 DB 동기화 버튼이 노출되지 않는다
 * @param tableCount        만들어진 테이블 수
 * @param relationshipCount 만들어진 관계(FK) 수
 * @param skipped           읽지 못한 문장·제약 요약 — 문서 생성은 가능한 만큼 진행된 결과다
 */
public record SqlImportResponse(
        ModelResponse model,
        int tableCount,
        int relationshipCount,
        List<String> skipped
) {
}
