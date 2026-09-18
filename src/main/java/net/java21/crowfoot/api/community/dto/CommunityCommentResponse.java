package net.java21.crowfoot.api.community.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/** 코멘트 응답 — plain text 본문 (08-core/08-community.md Section 3) */
public record CommunityCommentResponse(String commentId, String postId, String content, UserRefResponse author,
                                       Instant createdAt, Instant updatedAt) {
}
