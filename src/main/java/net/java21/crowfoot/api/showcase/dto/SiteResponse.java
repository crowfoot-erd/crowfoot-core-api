package net.java21.crowfoot.api.showcase.dto;

import java.time.Instant;

/** 문서의 사이트 (08-core/19-site-showcase.md Section 3.1) — 등록한 쪽이 보는 값이라 실패 사유·신고 수를 싣는다 */
public record SiteResponse(String siteId, String url, String title, String description, String siteName,
                           String faviconUrl, String thumbnailUrl, Instant capturedAt, String captureError,
                           boolean hidden, int reportCount, Instant createdAt, Instant updatedAt) {
}
