package net.java21.crowfoot.api.model.dto;

import java.util.List;

/**
 * 공유 문서 피드백 초기화 응답 (08-core/02-model.md Section 1.10.7) — GET .../comments 1회로
 * 피드백(반응 버튼 + 댓글 목록)이 띄워진다. reacted는 요청 회원 신원 기준(비회원 요청은 항상 false).
 */
public record ShareFeedbackResponse(
        long reactionCount,
        boolean reacted,
        List<ShareCommentResponse> comments) {
}
