package net.java21.crowfoot.api.term.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
/**
 * 용어 등록(수정 겸용) 요청 (08-core/01-workspace.md Section 4).
 * term은 물리명 토큰이라 서비스에서 trim + 소문자로 정규화하고 공백을 금지한다.
 * label은 추론 결과에 그대로 쓰이는 표기(한글 등, 공백 허용).
 * types는 DBMS 종류별 데이터 타입 맵(예: {"mysql":"VARCHAR(100)"}) — 키는 database_types 코드,
 * 값 trim 후 빈 값은 버린다(세부 검증은 서비스). 선택.
 */
public record UpsertTermRequest(
        @NotBlank @Size(max = 100) String term,
        @NotBlank @Size(max = 100) String label,
        Map<String, String> types
) {
}
