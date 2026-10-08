package net.java21.crowfoot.api.showcase.repository;

/** 썸네일 바이트와 MIME 타입 (Section 3.6) */
public record SiteThumbnail(byte[] image, String type) {
}
