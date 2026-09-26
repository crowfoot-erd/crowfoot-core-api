package net.java21.crowfoot.api.metrics.service;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;

/**
 * 국가 판정 GeoIP (08-core/10-metrics.md Section 6) — 클래스패스 번들 mmdb를 읽는 읽기 전용
 * 리더(DB-IP Country Lite, CC BY 4.0 — NOTICE 파일 참조. MaxMind GeoLite2 호환 형식).
 *
 * <p>로드 실패·파일 부재는 기능 장애가 아니다 — 국도를 unknown(null)으로 두고 나머지
 * 파이프라인이 계속 흐르게 한다(비콘 수집이 GeoIP에 묶여 죽지 않게).
 */
@Slf4j
@Service
public class GeoIpService {

    private static final String DB_PATH = "geoip/dbip-country-lite.mmdb";

    private DatabaseReader reader;

    public GeoIpService() {
        try (InputStream in = new ClassPathResource(DB_PATH).getInputStream()) {
            this.reader = new DatabaseReader.Builder(in).build();
            log.info("GeoIP DB 로드 완료({})", DB_PATH);
        } catch (IOException | RuntimeException ex) {
            this.reader = null;
            log.warn("GeoIP DB 로드 실패({}) — 국가 통계는 unknown으로 동작한다: {}", DB_PATH, ex.getMessage());
        }
    }

    /** ISO 3166-1 alpha-2, 판정 불가(사설 IP·미등록·로드 실패)는 null(= unknown) */
    public String country(String ip) {
        if (reader == null || ip == null || ip.isBlank()) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            CountryResponse response = reader.country(address);
            return response.getCountry().getIsoCode();
        } catch (IOException | GeoIp2Exception | RuntimeException ex) {
            return null;
        }
    }

    @PreDestroy
    void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }
}
