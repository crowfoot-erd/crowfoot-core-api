package net.java21.crowfoot.api.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 템플릿 공개 목록 항목 (08-core/09-templates.md Section 2.1) — 템플릿 워크스페이스 문서의 메타.
 * tableCount·relationshipCount는 content 배열 길이에서 산출한 카드 표기용 값이고,
 * shareToken은 그 문서의 활성 공유 링크 최근 1건(미발급이면 null — 카드에 미리보기가 없다).
 * description과 마찬가지로 null 필드는 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplateSummaryResponse(
        String modelId,
        String name,
        String description,
        String databaseType,
        int tableCount,
        int relationshipCount,
        String shareToken,
        Instant updatedAt
) {
}
