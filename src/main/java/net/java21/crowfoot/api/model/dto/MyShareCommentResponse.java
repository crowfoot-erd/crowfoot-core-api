package net.java21.crowfoot.api.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 내가 작성한 공유 문서 댓글 응답 (08-core/02-model.md Section 1.10.9) — 커뮤니티 "내 댓글" 메뉴.
 * 댓글에 어느 문서의 것인지(shareToken·문서 메타)를 함께 실어 웹이 댓글+ERD를 한 행에 그린다.
 * parentCommentId는 답글 표시 구분용(null이면 원댓글) — null 필드는 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MyShareCommentResponse(
        String commentId,
        String parentCommentId,
        String content,
        boolean edited,
        Instant createdAt,
        String shareToken,
        String modelName,
        String databaseType) {
}
