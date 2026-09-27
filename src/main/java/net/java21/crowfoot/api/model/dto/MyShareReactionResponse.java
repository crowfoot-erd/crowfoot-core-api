package net.java21.crowfoot.api.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 내가 좋아요한 공유 문서 응답 (08-core/02-model.md Section 1.10.9) — 커뮤니티 "좋아한 문서" 메뉴.
 * 갤러리 카드와 같은 문서 메타·카운터 3종에 좋아요 시각(reactedAt)을 얹는다.
 * description은 문서에 없으면 null — null 필드는 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MyShareReactionResponse(
        Instant reactedAt,
        String shareToken,
        String modelName,
        String description,
        String databaseType,
        long viewCount,
        long reactionCount,
        long commentCount) {
}
