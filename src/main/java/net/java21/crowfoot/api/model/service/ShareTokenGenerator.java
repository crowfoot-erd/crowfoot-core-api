package net.java21.crowfoot.api.model.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * 공유 링크 토큰 생성기 — 마이크로 ID 형식의 URL 안전 식별자.
 *
 * <p>base62(대소문자 알파벳+숫자) 22자로 128비트 엔트로피를 담는다(nanoID와 동일 강도 —
 * SecureRandom이라 추측 불가). URL 경로 세그먼트에 그대로 쓸 수 있고(인코딩 불필요),
 * 숫자 단일 ID 대신 짧게 보여 링크 주소를 간결하게 유지한다.
 */
@Component
public class ShareTokenGenerator {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /** 22자 × log2(62) ≈ 131비트 — UUID와 동등한 강도 */
    private static final int TOKEN_LENGTH = 22;

    private final SecureRandom secureRandom = new SecureRandom();

    /** 토큰 생성 — 충돌 가능성은 무시 가능 수준이지만 유니크 제약의 방어로 최대 5회 재시도한다. */
    public String generateUnique(Predicate<String> alreadyUsed) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String token = generate();
            if (!alreadyUsed.test(token)) {
                return token;
            }
        }
        throw new IllegalStateException("공유 토큰 생성에 실패했습니다 — 유니크 제약 충돌");
    }

    private String generate() {
        char[] token = new char[TOKEN_LENGTH];
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            token[i] = ALPHABET[secureRandom.nextInt(ALPHABET.length)];
        }
        return new String(token);
    }
}
