package net.java21.crowfoot.api.model.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 공유 토큰 생성기 테스트 — 길이·알파벳(URL 안전)·유니크 재시도. */
class ShareTokenGeneratorTest {

    private final ShareTokenGenerator generator = new ShareTokenGenerator();

    @Test
    @DisplayName("토큰은 base62 22자 — URL 경로에 안전한 문자만 쓴다")
    void generatesUrlSafeToken() {
        for (int i = 0; i < 100; i++) {
            String token = generator.generateUnique(t -> false);
            assertThat(token).hasSize(22).matches("[A-Za-z0-9]+");
        }
    }

    @Test
    @DisplayName("충돌 토큰은 다시 뽑는다 — 첫 토큰이 이미 쓰였으면 다른 값을 돌려준다")
    void retriesOnCollision() {
        String first = generator.generateUnique(t -> false);

        String second = generator.generateUnique(t -> t.equals(first));

        assertThat(second).hasSize(22).isNotEqualTo(first);
    }

    @Test
    @DisplayName("5회 연속 충돌하면 생성 실패로 예외를 던진다")
    void throwsAfterFiveCollisions() {
        assertThatThrownBy(() -> generator.generateUnique(t -> true))
                .isInstanceOf(IllegalStateException.class);
    }
}
