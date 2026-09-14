package net.java21.crowfoot.api.managed.provision;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 발급 계정 비밀번호 생성기 — 스키마 전용 DB 계정의 초기 비밀번호.
 * SecureRandom 20자에 대소문자·숫자·특수문자를 각 1자 이상 보장하고,
 * SQL 리터럴에 넣어 안전한 문자만 쓴다(작은따옴표·백슬래시 제외 — 이중 방어).
 */
public final class IssuedPasswords {

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "!@#$%^&*-_=+";
    private static final String ALL = UPPER + LOWER + DIGITS + SPECIAL;

    private static final int LENGTH = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private IssuedPasswords() {
    }

    public static String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        password.append(pick(UPPER));
        password.append(pick(LOWER));
        password.append(pick(DIGITS));
        password.append(pick(SPECIAL));
        for (int i = password.length(); i < LENGTH; i++) {
            password.append(pick(ALL));
        }
        // 각류 1자 이상은 셔플 후에도 보존된다 — 자릿수만 섞는다
        List<Character> chars = new ArrayList<>(LENGTH);
        password.chars().forEach(c -> chars.add((char) c));
        Collections.shuffle(chars, RANDOM);
        StringBuilder shuffled = new StringBuilder(LENGTH);
        chars.forEach(shuffled::append);
        return shuffled.toString();
    }

    private static char pick(String pool) {
        return pool.charAt(RANDOM.nextInt(pool.length()));
    }

    /** SQL 문자열 리터럴 이스케이프 — 생성기가 '·\를 쓰지 않으므로 실질 no-op 방어다 */
    static String escapeLiteral(String raw) {
        return raw.replace("'", "''").replace("\\", "\\\\");
    }
}
