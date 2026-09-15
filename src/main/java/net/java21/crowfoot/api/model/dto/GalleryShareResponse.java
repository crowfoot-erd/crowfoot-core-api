package net.java21.crowfoot.api.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 공유 갤러리 항목 (08-core/02-model.md Section 1.10.5) — 랜딩 페이지가 현재 공유 중인 문서를
 * 나열하는 공개 응답. 문서당 최근 발급 링크 1개씩이고 본문(content)은 미포함 — 카드는 메타만 보여준다.
 * description은 null 가능(문서 설명 없음) — null 필드는 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GalleryShareResponse(
        String shareToken,
        String modelName,
        String description,
        String databaseType,
        Instant updatedAt,
        Instant sharedAt
) {
}
