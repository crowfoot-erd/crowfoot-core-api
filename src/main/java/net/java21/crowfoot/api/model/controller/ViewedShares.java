package net.java21.crowfoot.api.model.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 최근 조회한 공유 문서 쿠키(crowfoot_share_views)의 값 (08-core/02-model.md Section 1.10.4) —
 * "토큰:epoch초" 항목을 {@code ~}로 잇는다. 조회 수(1.10.4 성공 조회마다 증가)가 같은 방문자의
 * 새로고침 연타로 부풀지 않게 하는 흔적이다 — 창({@value WINDOW}분) 안의 재조회는 세지 않는다.
 * 정밀 분석 자료가 아니라 중복 제거용이라 방문자 식별은 쿠키 하나로 충분하다(없으면 그냥 센다).
 */
final class ViewedShares {

    /** 같은 방문자의 재조회를 뭉개는 창 — 컨트롤러의 쿠키 Max-Age와 같은 값 */
    static final Duration WINDOW = Duration.ofMinutes(30);
    /** 쿠키 크기 상한 — 최근 50문서까지만 기억하고 오래된 순으로 버린다 */
    private static final int MAX_ENTRIES = 50;

    private final LinkedHashMap<String, Instant> viewedAt = new LinkedHashMap<>();

    private ViewedShares() {}

    /** 쿠키값 파싱 — 못 읽는 항목은 조용히 버린다(null·빈 값도 허용, 창 밖 항목도 제외) */
    static ViewedShares parse(String cookieValue, Instant now) {
        ViewedShares shares = new ViewedShares();
        if (cookieValue == null || cookieValue.isBlank()) {
            return shares;
        }
        for (String entry : cookieValue.split("~")) {
            int sep = entry.indexOf(':');
            if (sep <= 0 || sep == entry.length() - 1) {
                continue;
            }
            Instant at;
            try {
                at = Instant.ofEpochSecond(Long.parseLong(entry.substring(sep + 1)));
            } catch (NumberFormatException e) {
                continue;
            }
            if (now.isBefore(at.plus(WINDOW))) {
                shares.viewedAt.put(entry.substring(0, sep), at);
            }
        }
        return shares;
    }

    /** 이 토큰을 창 안에서 조회했는가 — 참이면 조회 수를 올리지 않는다 */
    boolean withinWindow(String token, Instant now) {
        Instant at = viewedAt.get(token);
        return at != null && now.isBefore(at.plus(WINDOW));
    }

    /** 토큰을 최근으로 기록(창 갱신 — 끝으로 옮긴다)하고 상한을 넘으면 오래된 순으로 버린 뒤 인코딩 */
    String with(String token, Instant now) {
        viewedAt.remove(token);
        viewedAt.put(token, now);
        while (viewedAt.size() > MAX_ENTRIES) {
            viewedAt.remove(viewedAt.keySet().iterator().next());
        }
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : viewedAt.entrySet()) {
            entries.add(entry.getKey() + ":" + entry.getValue().getEpochSecond());
        }
        return String.join("~", entries);
    }
}
