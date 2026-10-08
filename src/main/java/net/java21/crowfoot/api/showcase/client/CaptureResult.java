package net.java21.crowfoot.api.showcase.client;

/** 캡처 서비스 응답 (11-capture/00-capture-service.md Section 2.1) — image는 디코딩한 JPEG 바이트 */
public record CaptureResult(String finalUrl, String title, String description, String siteName,
                            String faviconUrl, String imageType, byte[] image) {
}
