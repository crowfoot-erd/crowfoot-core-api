package net.java21.crowfoot.api.model.dto;

/**
 * 마이그레이션 실행 요청 (08-core/02-model.md Section 1.15) — 본문은 선택이다.
 *
 * @param includeDestructive true면 삭제 문장(테이블·컬럼·제약·인덱스 삭제)까지 실행한다.
 *                           생략하거나 false면 추가와 변경만 실행하고 삭제 문장은 건너뛴다
 */
public record ExecuteMigrationRequest(Boolean includeDestructive) {
}
