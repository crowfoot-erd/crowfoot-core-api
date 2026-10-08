package net.java21.crowfoot.api.showcase.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 공개 목록 카드 (08-core/19-site-showcase.md Section 3.5) — 등록자·워크스페이스·신고 수·실패 사유는 싣지 않는다.
 * shareToken은 문서에 활성 공유 링크가 있을 때만 내린다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicSiteResponse(String siteId, String url, String title, String description, String siteName,
                                 String faviconUrl, String thumbnailUrl, String modelName, String databaseType,
                                 String shareToken, Instant createdAt) {
}
