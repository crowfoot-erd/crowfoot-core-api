package net.java21.crowfoot.api.community.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;
import java.util.List;

/** 게시글 요약(목록) — content 제외, 코멘트 건수 포함 (08-core/08-community.md Section 3.1).
 *  title은 ?lang=으로 해석된 단일 문자열, availableLangs는 그 글이 가진 언어 키(§2.1). */
public record CommunityPostSummaryResponse(String postId, String board, String title, List<String> availableLangs,
                                           UserRefResponse author, long commentCount, Instant createdAt,
                                           Instant updatedAt) {
}
