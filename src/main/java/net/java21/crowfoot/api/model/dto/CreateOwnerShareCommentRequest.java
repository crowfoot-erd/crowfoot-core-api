package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 오너 답글 등록 요청 (08-core/02-model.md Section 1.10.7) — 에디터 공유 다이얼로그(인증) 전용.
 * parentCommentId 필수 — 오너는 "답글"만 달 수 있고 최상단 댓글은 없다.
 */
public record CreateOwnerShareCommentRequest(
        @NotNull Long parentCommentId,
        @NotBlank @Size(max = 1000) String content) {
}
