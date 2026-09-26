package net.java21.crowfoot.api.metrics.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** GeoIP 판정 테스트 (08-core/10-metrics.md Section 6) — 번들 DB 로드와 unknown 폴백. */
class GeoIpServiceTest {

    private final GeoIpService service = new GeoIpService();

    @Test
    @DisplayName("공인 IP는 국가 코드(ISO alpha-2)로 판정된다 — 번들 DB 로드 확인")
    void publicIpResolvesCountry() {
        assertThat(service.country("8.8.8.8")).isNotBlank();
    }

    @Test
    @DisplayName("사설·없음·빈 값은 null(unknown) — 판정 실패가 비콘 수집을 막지 않는다")
    void unresolvableIpFallsBackToUnknown() {
        assertThat(service.country(null)).isNull();
        assertThat(service.country("")).isNull();
        assertThat(service.country("192.168.0.1")).isNull();   // 사설 — DB 미등록
        assertThat(service.country("not-an-ip")).isNull();
    }
}
