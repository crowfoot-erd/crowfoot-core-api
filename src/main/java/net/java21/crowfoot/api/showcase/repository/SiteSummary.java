package net.java21.crowfoot.api.showcase.repository;

import java.time.Instant;

/**
 * 목록 프로젝션 — 썸네일 바이트를 읽지 않는다(Section 3.5·3.8). JPQL 생성자 식이 찾도록 클래스 레벨 public record다.
 * 썸네일 유무는 thumbnailType으로 판정한다.
 */
public record SiteSummary(Long id, Long modelId, String url, String title, String description, String siteName,
                          String faviconUrl, String thumbnailType, Instant capturedAt, String captureError,
                          boolean hidden, Long hiddenBy, Instant hiddenAt, int reportCount, Long createdBy,
                          Instant createdAt) {
}
