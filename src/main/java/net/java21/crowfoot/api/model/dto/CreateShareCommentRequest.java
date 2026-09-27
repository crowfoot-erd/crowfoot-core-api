package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 공유 문서 익명 댓글 등록 요청 (08-core/02-model.md Section 1.10.7) — 별명+내용만, 계정 불필요 */
public record CreateShareCommentRequest(
        @NotBlank @Size(max = 30) String nickname,
        @NotBlank @Size(max = 1000) String content) {
}
