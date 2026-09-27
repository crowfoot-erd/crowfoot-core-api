package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 공유 문서 댓글 수정 요청 (08-core/02-model.md Section 1.10.7) — 본문 내용만 고친다(별명·비밀번호는 못 바꾼다).
 * 회원(X-USER-ID)은 본인 계정 판정이라 password 없이, 비회원은 password 필수(불일치 403) — 선택 인증 경로.
 */
public record UpdateShareCommentRequest(
        @NotBlank @Size(max = 1000) String content,
        @Size(min = 4, max = 100) String password) {
}
