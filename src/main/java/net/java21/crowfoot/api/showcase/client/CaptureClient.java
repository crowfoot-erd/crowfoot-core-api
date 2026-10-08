package net.java21.crowfoot.api.showcase.client;

/** 캡처 서비스 호출 (11-capture/00-capture-service.md Section 2.1) — 실패는 {@link CaptureException} */
public interface CaptureClient {

    CaptureResult capture(String url);
}
