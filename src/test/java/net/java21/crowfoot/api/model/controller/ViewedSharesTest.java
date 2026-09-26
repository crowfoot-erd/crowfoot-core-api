package net.java21.crowfoot.api.model.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최근 조회한 공유 문서 쿠키 값 파싱·인코딩 단위 테스트 (08-core/02-model.md Section 1.10.4) —
 * 30분 창 판정(창 안 재조회 = 미카운트)·못 읽는 값 무시·갱신 순서·50개 상한을 검증한다.
 */
class ViewedSharesTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @Test
    @DisplayName("창 안에 조회한 토큰은 withinWindow 참 — 재조회는 조회 수에서 빠진다")
    void withinWindowTrueForRecentEntry() {
        String cookie = "tokA:" + NOW.minusSeconds(60).getEpochSecond(); // 1분 전 조회
        ViewedShares viewed = ViewedShares.parse(cookie, NOW);

        assertThat(viewed.withinWindow("tokA", NOW)).isTrue();
        assertThat(viewed.withinWindow("tokB", NOW)).isFalse(); // 본 적 없는 토큰
    }

    @Test
    @DisplayName("창(30분)을 지난 항목은 파싱에서 버린다 — 쿠키가 남아 있어도 다시 센다")
    void parseDropsEntriesOutsideWindow() {
        String cookie = "tokA:" + NOW.minus(ViewedShares.WINDOW).getEpochSecond(); // 딱 30분 전
        ViewedShares viewed = ViewedShares.parse(cookie, NOW);

        assertThat(viewed.withinWindow("tokA", NOW)).isFalse();
    }

    @Test
    @DisplayName("null·빈 값·못 읽는 항목은 조용히 무시한다 — 쿠키 조작에 깨지지 않는다")
    void parseToleratesMalformedValue() {
        assertThat(ViewedShares.parse(null, NOW).withinWindow("tokA", NOW)).isFalse();
        assertThat(ViewedShares.parse("", NOW).withinWindow("tokA", NOW)).isFalse();
        assertThat(ViewedShares.parse("tokA:xxx~noColon~:123~tokB:1.5", NOW)
                .withinWindow("tokA", NOW)).isFalse();
    }

    @Test
    @DisplayName("상한(최근 50개)을 넘으면 가장 오래된(맨 앞) 토큰부터 버린다 — 쿠키 크기 폭발 방지")
    void withCapsEntriesAtFifty() {
        StringBuilder cookie = new StringBuilder();
        for (int i = 0; i < 55; i++) {
            if (i > 0) {
                cookie.append('~');
            }
            cookie.append("tok").append(i).append(':').append(NOW.getEpochSecond());
        }

        String encoded = ViewedShares.parse(cookie.toString(), NOW).with("tokNew", NOW);

        assertThat(encoded.split("~")).hasSize(50);
        assertThat(encoded).startsWith("tok6:"); // 55+1건 중 오래된 6개(tok0..tok5) 탈락
        assertThat(encoded).endsWith("tokNew:" + NOW.getEpochSecond());
    }

    @Test
    @DisplayName("with는 토큰을 최근으로 갱신해 인코딩한다 — 기존 항목 보존·조회 순서 유지")
    void withEncodesTokenLast() {
        ViewedShares viewed = ViewedShares.parse("tokA:100", NOW); // 창 밖이라 버려진다
        String encoded = viewed.with("tokB", NOW);

        assertThat(encoded).isEqualTo("tokB:" + NOW.getEpochSecond());

        ViewedShares kept = ViewedShares.parse("tokA:" + NOW.getEpochSecond(), NOW);
        assertThat(kept.with("tokB", NOW))
                .isEqualTo("tokA:" + NOW.getEpochSecond() + "~tokB:" + NOW.getEpochSecond());
        // 재조회(창 갱신)는 토큰을 끝으로 옮긴다 — 오래된 순서로 상한 초과분을 버리기 위해서
        assertThat(ViewedShares.parse(
                "tokB:" + NOW.getEpochSecond() + "~tokA:" + NOW.getEpochSecond(), NOW)
                .with("tokB", NOW))
                .isEqualTo("tokA:" + NOW.getEpochSecond() + "~tokB:" + NOW.getEpochSecond());
    }
}
