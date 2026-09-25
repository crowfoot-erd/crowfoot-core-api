package net.java21.crowfoot.api.community.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;
import java.util.List;

/** 게시글 상세 — 마크다운 원문(content) 포함 (08-core/08-community.md Section 3.3).
 *  title·content는 ?lang=으로 해석된 단일 문자열(폴백 체인 §2.1), availableLangs는 실제 존재하는 언어 키. */
public record CommunityPostDetailResponse(String postId, String board, String title, List<String> availableLangs,
                                          String content, UserRefResponse author, Instant createdAt,
                                          Instant updatedAt) {
}
