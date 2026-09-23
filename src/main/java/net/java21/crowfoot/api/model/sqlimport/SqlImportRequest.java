package net.java21.crowfoot.api.model.sqlimport;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SQL Import 생성 요청 (08-core/02-model.md Section 1.12) — DDL 텍스트로 신규 문서를 만든다.
 * name을 생략하면 "SQL ERD"가 된다. databaseType은 타입 정규화 기준(활성 목록에서 검증).
 */
public record SqlImportRequest(
        @Size(min = 1, max = 100) String name,
        @Size(max = 500) String description,
        @NotBlank String databaseType,
        @NotBlank @Size(max = 1_000_000) String ddl
) {
}
