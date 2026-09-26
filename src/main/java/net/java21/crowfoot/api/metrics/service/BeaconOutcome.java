package net.java21.crowfoot.api.metrics.service;

import java.util.UUID;

/**
 * 비콘 1건 처리 결과 — 컨트롤러가 Set-Cookie를 만드는 데 쓰는 판정 산출물.
 * 봇은 쿠키를 주지 않고, 세션 쿠키는 매 응답 재발급(30분 슬라이딩)으로 갱신된다.
 */
public record BeaconOutcome(boolean bot, UUID visitorUuid, boolean issueVisitorCookie, UUID sessionUuid) {

    public static BeaconOutcome ofBot() {
        return new BeaconOutcome(true, null, false, null);
    }
}
