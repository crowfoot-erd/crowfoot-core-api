package net.java21.crowfoot.api.model.dto;

import java.util.List;

/**
 * 마이그레이션 DDL 생성 결과 (08-core/02-model.md Section 1.7.1) — 생성만 제공, 실행은 범위 밖.
 *
 * @param sql            전체 스크립트 — 파괴적 연산은 마지막 별도 블록(경고 배너 포함)
 * @param warnings       DESTRUCTIVE(파괴 문장 존재)·NOT_INTROSPECTED(인덱스 제외)·COMMON_DIALECT·VALIDATION
 * @param statementCount 실행 단위 문장 수(헤더·배너 주석 제외)
 * @param fromLabel      이행 원천 — "v2"(버전 비교) 또는 "DB"(문서↔실제 DB 비교)
 * @param toLabel        이행 대상 — "v3" 또는 "문서"
 */
public record MigrationDdlResponse(String sql, List<DdlWarningResponse> warnings, int statementCount,
                                   String fromLabel, String toLabel) {
}
