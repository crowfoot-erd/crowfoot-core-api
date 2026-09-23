package net.java21.crowfoot.api.model.sqlimport;

import java.util.List;

/**
 * SQL Import 미리보기 결과 (08-core/02-model.md Section 1.12) — 저장 전 파싱 결과 요약.
 * 개수는 생성 시점과 같은 경로(assemble)로 계산한다 — 미리보기에서 본 숫자가 그대로 만들어진다.
 *
 * @param databaseType      요청 그대로(정규화 기준)
 * @param tableCount        만들어질 테이블 수
 * @param relationshipCount 만들어질 관계(FK) 수
 * @param tables            테이블별 요약 — 문서 순서
 * @param skipped           읽지 못한 문장·제약 요약 — CREATE TABLE 0개면 400(SQL_IMPORT_NO_TABLES)으로 실패
 */
public record SqlImportPreviewResponse(
        String databaseType,
        int tableCount,
        int relationshipCount,
        List<PreviewTable> tables,
        List<String> skipped
) {

    /**
     * 미리보기 테이블 요약 1건.
     *
     * @param name             물리명
     * @param comment          테이블 코멘트(MySQL COMMENT·PG COMMENT ON) — 없으면 null
     * @param columnCount      컬럼 수(FK 포함)
     * @param primaryKeyColumns PK 컬럼 물리명 — 없으면 빈 목록
     * @param foreignKeyCount  이 테이블이 자식인 FK 수
     */
    public record PreviewTable(
            String name,
            String comment,
            int columnCount,
            List<String> primaryKeyColumns,
            int foreignKeyCount
    ) {
    }
}
