package net.java21.crowfoot.api.showcase.dto;

import java.time.Instant;

/** 관리자 목록 항목 (08-core/19-site-showcase.md Section 3.8) — 숨김 정보와 등록 위치를 더한다 */
public record AdminSiteResponse(String siteId, String url, String title, String description, String siteName,
                                String faviconUrl, String thumbnailUrl, String modelName, String databaseType,
                                String workspaceId, String modelId, String createdBy, int reportCount,
                                boolean hidden, Instant hiddenAt, boolean autoHidden, String captureError,
                                Instant createdAt) {
}
