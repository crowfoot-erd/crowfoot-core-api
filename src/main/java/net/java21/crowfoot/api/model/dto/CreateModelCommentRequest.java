package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 문서 댓글 등록 요청 — 멤버 문서 경로(08-core/02-model.md Section 1.10.7) — 문서 열기 댓글 탭 전용.
 * 회원 전용 경로라 nickname·password 개념이 없다(계정 댓글). parentCommentId는 오너 답글(문서 작성자·
 * 관리자)에만 허용되고, 그 외 멤버가 보내면 403 — 일반 멤버 원댓글은 null(생략)이다.
 */
public record CreateModelCommentRequest(
        @NotBlank @Size(max = 1000) String content,
        Long parentCommentId) {
}
