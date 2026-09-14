package net.java21.crowfoot.api.connection.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 커넥션 비밀번호 암호기 테스트 (08-core/06-connection.md Section 2) —
 * AES-256-GCM 왕복·랜덤 IV·키 불일치 감지(GCM 태그)·키 형식 검증.
 */
class ConnectionCryptoTest {

    private static final String DEV_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    @DisplayName("암호화→복호화 왕복이 원문을 돌려준다")
    void roundTrip() {
        ConnectionCrypto crypto = new ConnectionCrypto(DEV_KEY);
        byte[] encrypted = crypto.encrypt("crowfoot123!");
        assertThat(encrypted).isNotNull();
        assertThat(crypto.decrypt(encrypted)).isEqualTo("crowfoot123!");
    }

    @Test
    @DisplayName("같은 평문도 매번 다른 암호문이 된다 — IV가 무작위다")
    void randomIv() {
        ConnectionCrypto crypto = new ConnectionCrypto(DEV_KEY);
        assertThat(crypto.encrypt("same")).isNotEqualTo(crypto.encrypt("same"));
    }

    @Test
    @DisplayName("다른 키로 복호화하면 실패한다 — GCM 인증 태그 불일치")
    void wrongKeyRejected() {
        byte[] encrypted = new ConnectionCrypto(DEV_KEY).encrypt("secret");
        ConnectionCrypto other = new ConnectionCrypto(Base64.getEncoder()
                .encodeToString("fedcba9876543210fedcba9876543210".getBytes()));
        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("키는 base64 32바이트여야 한다 — 기동 검증")
    void invalidKeyLength() {
        assertThatThrownBy(() -> new ConnectionCrypto(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ConnectionCrypto("not-base64!!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("손상된 암호문(짧은 길이)은 복호화 거부한다")
    void corruptCiphertext() {
        ConnectionCrypto crypto = new ConnectionCrypto(DEV_KEY);
        assertThatThrownBy(() -> crypto.decrypt(new byte[8])).isInstanceOf(IllegalStateException.class);
    }
}
