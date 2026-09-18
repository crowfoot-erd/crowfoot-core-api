package net.java21.crowfoot.api.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 코멘트 수정 요청 (08-core/08-community.md Section 3) — 작성자 본인 또는 관리자 */
public record UpdateCommunityCommentRequest(
        @NotBlank @Size(max = 2000) String content) {
}
