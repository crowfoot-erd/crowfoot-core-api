package net.java21.crowfoot.api.community.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/** 게시글 요약(목록) — content 제외, 코멘트 건수 포함 (08-core/08-community.md Section 3) */
public record CommunityPostSummaryResponse(String postId, String board, String title, UserRefResponse author,
                                           long commentCount, Instant createdAt, Instant updatedAt) {
}
