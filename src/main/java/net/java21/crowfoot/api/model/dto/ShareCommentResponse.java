package net.java21.crowfoot.api.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 공유 문서 댓글 응답 (08-core/02-model.md Section 1.10.7) — 공개 뷰어·공유 다이얼로그 공통.
 * nickname은 비회원이면 저장된 별명, 회원·오너이면 작성자의 users.name.
 * authorType — owner(문서 작성자의 댓글·답글) / member(그 외 회원) / guest(비회원).
 * parentCommentId로 웹이 1단계 중첩해 그린다(null이면 원댓글) — null 필드는 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShareCommentResponse(
        String commentId,
        String parentCommentId,
        String nickname,
        String authorType,
        String content,
        boolean edited,
        Instant createdAt) {
}
