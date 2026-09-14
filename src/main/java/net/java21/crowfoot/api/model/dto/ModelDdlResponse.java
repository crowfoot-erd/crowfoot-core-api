package net.java21.crowfoot.api.model.dto;

import java.util.List;

/**
 * 모델 DDL 생성 결과 (08-core/02-model.md Section 1.7).
 *
 * @param sql              전체 스크립트 — 헤더 주석 포함, 생성은 마지막 저장 본문 기준
 * @param warnings         생성 경고 — 공용 방언 폴백·검증 오류·빈 테이블
 * @param tableCount       문서 테이블 수(빈 테이블 포함)
 * @param relationshipCount 문서 관계 수
 */
public record ModelDdlResponse(String sql, List<DdlWarningResponse> warnings, int tableCount, int relationshipCount) {
}
