package net.java21.crowfoot.api.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 코멘트 생성 요청 (08-core/08-community.md Section 3) — FEEDBACK 게시글에만 허용 */
public record CreateCommunityCommentRequest(
        @NotBlank @Size(max = 2000) String content) {
}
