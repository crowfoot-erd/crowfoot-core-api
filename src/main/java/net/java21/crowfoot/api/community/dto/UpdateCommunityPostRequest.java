package net.java21.crowfoot.api.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 게시글 수정 요청 (08-core/08-community.md Section 3) — board 필드가 없어 게시판 이동은 원천 불가.
 * 작성자 본인 또는 관리자만 호출할 수 있다.
 */
public record UpdateCommunityPostRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 1_000_000) String content) {
}
