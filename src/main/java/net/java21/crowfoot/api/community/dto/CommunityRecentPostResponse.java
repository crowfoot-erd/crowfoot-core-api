package net.java21.crowfoot.api.community.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/** 최근글(대시보드 통합 위젯) — 게시판 무관 최신순, content 제외 (08-core/08-community.md Section 3) */
public record CommunityRecentPostResponse(String postId, String board, String title, UserRefResponse author,
                                          long commentCount, Instant createdAt) {
}
