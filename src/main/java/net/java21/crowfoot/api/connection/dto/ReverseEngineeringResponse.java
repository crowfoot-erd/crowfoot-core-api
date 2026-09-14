package net.java21.crowfoot.api.connection.dto;

import net.java21.crowfoot.api.model.dto.ModelResponse;

import java.util.List;

/**
 * 리버스 엔지니어링 결과 (08-core/06-connection.md Section 3.6) — 생성된 문서(전체 형식) + 요약.
 *
 * @param model            생성·저장된 신규 문서 — 문서 DB 종류는 커넥션 종류를 물려받는다
 * @param tableCount       읽어 들인 테이블 수
 * @param relationshipCount 만들어진 관계(FK) 수
 * @param skipped          제외 항목과 사유 — 정상 흐름에서는 보통 비어 있다
 */
public record ReverseEngineeringResponse(
        ModelResponse model,
        int tableCount,
        int relationshipCount,
        List<String> skipped
) {
}
