package net.java21.crowfoot.api.connection.dto;

import java.util.List;

/**
 * 스키마 조회 결과 (08-core/06-connection.md Section 3.7) — 문서 동기화 원천.
 * 리버스 엔지니어링(3.6)과 같은 조립 결과에서 문서 생성만 뺀 평면 형태다.
 *
 * <p>content는 Canonical content v1 문자열이지만 **저장이 아닌 비교 원천**이다 —
 * 문서와의 병합(차분 계산·적용)은 에디터가 수행한다(05-editor/04-dbms-engineering.md Section 3.3).
 *
 * @param content           조립된 Canonical content v1 (diagram 포함 — 신규 객체 원천으로만 쓰인다)
 * @param tableCount        읽어 들인 테이블 수
 * @param relationshipCount 만들어진 관계(FK) 수
 * @param skipped           제외 항목과 사유 — 정상 흐름에서는 보통 비어 있다
 */
public record ConnectionSchemaResponse(
        String content,
        int tableCount,
        int relationshipCount,
        List<String> skipped
) {
}
