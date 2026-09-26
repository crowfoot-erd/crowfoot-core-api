package net.java21.crowfoot.api.metrics.service;

import net.java21.crowfoot.api.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * 일일 솔트 IP 해시 (08-core/10-metrics.md Section 2·5.1) — 방문자 쿠키를 받지 못한 방문자의
 * UUV 근사 식별자. 솔트는 {@code HMAC(secret, 날짜)}로 유도해 재기동·다중 인스턴스에서 같은
 * 하루면 같은 값이 나오게 한다(별도 저장 없음). 해시는 해시라 원복 불가다.
 */
@Component
public class DailyIpHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secret;

    public DailyIpHasher(AppProperties properties) {
        String configured = properties.metrics() == null ? null : properties.metrics().ipHashSecret();
        this.secret = (configured == null || configured.isBlank() ? "crowfoot-metrics-dev-secret" : configured)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** {@code HEX(HMAC(HEX(HMAC(secret, date)), ip))} — 날짜마다 값이 바뀌는 비가역 식별자 */
    public String hash(String ip, LocalDate dateKst) {
        byte[] salt = hmac(secret, dateKst.toString());
        return HEX.formatHex(hmac(salt, ip));
    }

    private byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC 계산 실패", ex);
        }
    }
}
