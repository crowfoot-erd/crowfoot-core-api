package net.java21.crowfoot.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 캡처 서비스(crowfoot-capture) 연동 설정 (08-core/19-site-showcase.md Section 4) — 클러스터 내부 직접 호출.
 *
 * @param baseUrl 캡처 서비스 주소 (로컬 http://localhost:8086, 운영 http://crowfoot-capture)
 */
@ConfigurationProperties(prefix = "crowfoot.capture")
public record CaptureProperties(String baseUrl) {

    public CaptureProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8086";
        }
    }
}
