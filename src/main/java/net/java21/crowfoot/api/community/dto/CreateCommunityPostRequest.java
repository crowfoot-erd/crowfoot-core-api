package net.java21.crowfoot.api.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import net.java21.crowfoot.common.i18n.LocalizedText;

/**
 * 게시글 생성 요청 (08-core/08-community.md Section 3.4) — board는 생성 시에만 지정(이동 불가).
 * RELEASE_NOTE 요청은 관리자만 성공한다(서비스에서 AdminGuard 판정).
 * title·content는 다국어 텍스트(문자열 또는 언어 객체 — §2.1 쓰기 다형)로, 언어별 값 길이는 서비스가 검증한다.
 */
public record CreateCommunityPostRequest(
        @NotBlank @Pattern(regexp = "RELEASE_NOTE|FEEDBACK", message = "알 수 없는 게시판입니다") String board,
        LocalizedText title,
        LocalizedText content) {
}
