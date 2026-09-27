package net.java21.crowfoot.api.model.service;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

/**
 * 비회원 댓글 비밀번호 해시 (08-core/02-model.md Section 1.10.7) — PBKDF2WithHmacSHA256.
 * 이 시스템 최초의 비밀번호라 외부 의존성 없이 JDK 내장 구현으로 쓴다(계정 로그인은 OAuth2 전용).
 * 저장형식은 자기서술형 문자열 {@code pbkdf2-sha256$<iterations>$<saltHex>$<hashHex>} —
 * 반복 수를 바꿔도 기존 해시 검증이 가능하다. 검증은 상수시간 비교(MessageDigest.isEqual).
 */
@Component
public class GuestPasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;   // OWASP 2023 권장 하한
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();

    /** 비밀번호 → 자기서술형 해시 문자열(솔트 포함) */
    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derived = derive(rawPassword, HexFormat.of().formatHex(salt), ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + HexFormat.of().formatHex(salt) + "$"
                + HexFormat.of().formatHex(derived);
    }

    /** 원문 비밀번호가 저장된 해시와 일치하는가 — 형식이 다르면(레거시·조작) 무조건 false */
    public boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] expected = HexFormat.of().parseHex(parts[3]);
            byte[] actual = derive(rawPassword, parts[2], iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) { // NumberFormatException 포함 — 형식 파손 전부
            return false;
        }
    }

    private byte[] derive(String rawPassword, String saltHex, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(),
                    HexFormat.of().parseHex(saltHex), iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("PBKDF2 파생 실패", e);
        }
    }
}
