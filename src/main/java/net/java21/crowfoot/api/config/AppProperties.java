package net.java21.crowfoot.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * crowfoot.* 애플리케이션 설정.
 *
 * @param rotation Refresh Rotation 판정 설정
 * @param auth     인증 서버(crowfoot-auth) 연동 설정 — 내부망 직접 호출 (Gateway 경유 없음)
 */
@ConfigurationProperties(prefix = "crowfoot")
public record AppProperties(Rotation rotation, Auth auth) {

    /** @param graceSeconds Rotation 유예 기준 시간(초) — 유예 내 구 Refresh 재사용은 GRACE로 정상 처리 */
    public record Rotation(int graceSeconds) {
    }

    /** @param baseUrl 인증 서버 내부 엔드포인트 (예: http://localhost:8081) */
    public record Auth(String baseUrl) {
    }
}
