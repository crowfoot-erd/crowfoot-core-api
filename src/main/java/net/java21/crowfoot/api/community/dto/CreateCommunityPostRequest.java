package net.java21.crowfoot.api.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 게시글 생성 요청 (08-core/08-community.md Section 3) — board는 생성 시에만 지정(이동 불가).
 * RELEASE_NOTE 요청은 관리자만 성공한다(서비스에서 AdminGuard 판정).
 */
public record CreateCommunityPostRequest(
        @NotBlank @Pattern(regexp = "RELEASE_NOTE|FEEDBACK", message = "알 수 없는 게시판입니다") String board,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 1_000_000) String content) {
}
