package net.java21.crowfoot.api.metrics.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.metrics.dto.VisitBeaconRequest;
import net.java21.crowfoot.api.metrics.service.BeaconOutcome;
import net.java21.crowfoot.api.metrics.service.VisitMetricsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;

/**
 * 접속 통계 비콘 API (08-core/10-metrics.md Section 3) — 무인증 공개 경로(XUserIdFilter 제외,
 * Gateway 화이트리스트 POST /api/v1/core/metrics/visit). 응답은 본문 없는 204 — 웹은
 * fire-and-forget(sendBeacon)으로 보내고 실패를 무시한다.
 */
@RestController
@RequiredArgsConstructor
public class MetricsController {

    /** 방문자 식별 쿠키 — 400일, 이 요청에서 처음 발급되면 신규 방문자 */
    static final String VISITOR_COOKIE = "crowfoot_visitor";

    /** 세션 식별 쿠키 — 30분 슬라이딩, 매 응답 재발급으로 갱신 */
    static final String SESSION_COOKIE = "crowfoot_stats_session";

    /** 쿠키 Path는 브라우저가 보는 외부 경로 기준(crowfoot_share_views와 같은 규칙) */
    private static final String COOKIE_PATH = "/api/v1/core/metrics";
    private static final Duration VISITOR_MAX_AGE = Duration.ofDays(400);
    private static final Duration SESSION_MAX_AGE = Duration.ofMinutes(30);

    private final VisitMetricsService visitMetricsService;

    /** 방문 비콘 수집 — 판정 후 204. 봇이면 쿠키 없음, 아니면 세션 쿠키(항상)·방문자 쿠키(발급 시) */
    @PostMapping("/core/metrics/visit")
    public ResponseEntity<Void> visit(
            @Valid @RequestBody VisitBeaconRequest request,
            @CookieValue(name = VISITOR_COOKIE, required = false) String visitorCookie,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionCookie,
            HttpServletRequest servletRequest) {
        BeaconOutcome outcome = visitMetricsService.record(
                request, visitorCookie, sessionCookie,
                servletRequest.getHeader("User-Agent"),
                servletRequest.getHeader("Accept-Language"),
                clientIp(servletRequest));

        if (outcome.bot()) {
            return ResponseEntity.noContent().build();
        }
        HttpHeaders setCookie = new HttpHeaders();
        if (outcome.issueVisitorCookie()) {
            setCookie.add(HttpHeaders.SET_COOKIE, cookie(
                    VISITOR_COOKIE, outcome.visitorUuid().toString(), VISITOR_MAX_AGE).toString());
        }
        setCookie.add(HttpHeaders.SET_COOKIE, cookie(
                SESSION_COOKIE, outcome.sessionUuid().toString(), SESSION_MAX_AGE).toString());
        return ResponseEntity.noContent().headers(setCookie).build();
    }

    /**
     * 클라이언트 IP — Gateway가 X-Forwarded-For 첫 홉으로 실 클라이언트를 내려준다
     * (03-gateway/requirements.md §4 전달 계약). 직접 접속(로컬·디버그)은 소켓 주소로 폴백.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static ResponseCookie cookie(String name, String value, Duration maxAge) {
        // 운영은 항상 https이고 로컬 localhost도 신뢰 컨텍스트라 Secure 고정 — crowfoot_share_views 쿠키와 같은 근거
        return ResponseCookie.from(name, value)
                .path(COOKIE_PATH)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(maxAge)
                .build();
    }
}
