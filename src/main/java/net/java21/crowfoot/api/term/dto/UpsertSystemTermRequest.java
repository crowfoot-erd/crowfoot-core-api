package net.java21.crowfoot.api.term.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 시스템 사전 등록(수정 겸용) 요청 (08-core/01-workspace.md Section 4.5).
 * term은 물리명 토큰이라 서비스에서 trim + 소문자로 정규화하고 공백을 금지한다.
 * labels는 언어→라벨 맵(최소 1개 언어 — 세부 검증은 서비스),
 * types는 DBMS 종류별 데이터 타입 맵(키는 database_types 코드 — 세부 검증은 서비스), 둘 다 선택은 아니다(labels만 필수).
 */
public record UpsertSystemTermRequest(
        @NotBlank @Size(max = 100) String term,
        @NotNull Map<String, String> labels,
        Map<String, String> types
) {
}
