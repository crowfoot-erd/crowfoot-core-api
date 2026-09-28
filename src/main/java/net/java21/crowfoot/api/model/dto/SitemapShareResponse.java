package net.java21.crowfoot.api.model.dto;

import java.time.Instant;

/** 사이트맵 행 (08-core/02-model.md Section 1.10.10) — 활성 공유 문서의 색인 URL 원료 */
public record SitemapShareResponse(String shareToken, Instant lastmod) {
}
