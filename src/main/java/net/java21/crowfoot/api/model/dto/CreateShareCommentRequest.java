package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 공유 문서 댓글 등록 요청 (08-core/02-model.md Section 1.10.7) — 회원·비회원이 같은 경로를 쓴다(선택 인증).
 * 회원 요청(X-USER-ID 있음)은 content만 쓰고 nickname·password는 무시되고,
 * 비회원 요청은 nickname·password가 필수다(누락·불충족은 서비스가 400 — 빈칸 검증은 빈 문자열도 잡도록 여기선 형식만).
 */
public record CreateShareCommentRequest(
        @Size(max = 30) String nickname,
        @NotBlank @Size(max = 1000) String content,
        @Size(min = 4, max = 100) String password) {
}
