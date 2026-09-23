package net.java21.crowfoot.api.model.sqlimport;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SQL Import 미리보기 요청 (08-core/02-model.md Section 1.12) — 저장 없이 파싱·조립까지만.
 * 생성 요청과 같은 검증 규칙을 쓴다(활성 databaseType, DDL 상한).
 */
public record SqlImportPreviewRequest(
        @NotBlank String databaseType,
        @NotBlank @Size(max = 1_000_000) String ddl
) {
}
