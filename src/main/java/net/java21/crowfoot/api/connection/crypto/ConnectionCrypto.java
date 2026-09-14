package net.java21.crowfoot.api.connection.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 커넥션 비밀번호 암호기 (08-core/06-connection.md Section 2) — AES-256-GCM.
 *
 * <p>키는 환경변수 {@code CROWFOOT_CONNECTION_SECRET_KEY}(base64 32바이트)에서만 주입받는다
 * (yml 평문 금지 — 기본값은 개발 전용 폴백). 저장 포맷은 {@code iv[12] ‖ ciphertext‖tag[16]}
 * 바이트 열이고, 복호화는 introspection 시점 서버 내부에서만 일어난다.
 * 운영에서 키를 바꾸면 기존 암호문을 더는 풀 수 없다(커넥션 재등록 필요).
 */
@Component
public class ConnectionCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public ConnectionCrypto(@Value("${crowfoot.connection.secret-key}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CROWFOOT_CONNECTION_SECRET_KEY는 base64여야 합니다", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("CROWFOOT_CONNECTION_SECRET_KEY는 base64 인코딩된 32바이트여야 합니다");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, out, IV_BYTES, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("커넥션 비밀번호 암호화에 실패했습니다", e);
        }
    }

    public String decrypt(byte[] stored) {
        if (stored == null || stored.length <= IV_BYTES) {
            throw new IllegalStateException("커넥션 비밀번호 암호문이 손상되었습니다");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(stored, 0, IV_BYTES)));
            byte[] plain = cipher.doFinal(Arrays.copyOfRange(stored, IV_BYTES, stored.length));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("커넥션 비밀번호 복호화에 실패했습니다 — 암호화 키가 변경되지 않았는지 확인하세요", e);
        }
    }
}
