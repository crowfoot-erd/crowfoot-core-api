package net.java21.crowfoot.api.community.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/** 게시글 상세 — 마크다운 원문(content) 포함 (08-core/08-community.md Section 3) */
public record CommunityPostDetailResponse(String postId, String board, String title, String content,
                                          UserRefResponse author, Instant createdAt, Instant updatedAt) {
}
