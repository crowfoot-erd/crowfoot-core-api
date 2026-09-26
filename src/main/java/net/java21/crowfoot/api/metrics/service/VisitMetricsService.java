package net.java21.crowfoot.api.metrics.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.metrics.domain.VisitEvent;
import net.java21.crowfoot.api.metrics.dto.VisitBeaconRequest;
import net.java21.crowfoot.api.metrics.repository.DailyMetricRollupRepository;
import net.java21.crowfoot.api.metrics.repository.VisitEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * 방문 비콘 판정 파이프라인 (08-core/10-metrics.md Section 4) — 봇 판정 → 30초 중복 제거 →
 * 차원 파생 → 원본 INSERT + 롤업 증분(같은 트랜잭션). UUV·세션의 증분 정확성은 플래그
 * 3종(dayFirst·visitorNew·sessionNew)으로 보장한다(Section 4.5).
 *
 * <p>식별자 규칙: 방문자 쿠키가 있으면 그 UUID로, 없으면 일일 IP 해시으로 직전 이벤트를 본다.
 * 쿠키를 처음 발급하는 요청에도 UUID를 행에 남긴다 — 브라우저가 쿠키를 받아들이면 다음 요청부터
 * UUID 경로로 식별이 이어진다. 쿠키를 거부하는 방문자는 매번 IP 해시 경로가 되고, 신규/재방문
 * 미분류 근사(visitor_estimated)로만 센다(Section 3 — 쿠키 거부 환경 계약).
 *
 * <p>동시 같은 방문자 비콄의 플래그 경합은 앱 레벨에서 허용한다(롤업 합이 원본 distinct와
 * 어긋날 수 있는 창이 초 단위다 — 게이트 테스트는 순차 요청으로 대조 검증).
 */
@Service
@RequiredArgsConstructor
public class VisitMetricsService {

    /** 하루 경계·정리 기준 시간대 — Section 2(KST 고정) */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** PV 중복 제거 창(초) — Section 4.2. 조회수(view_count)의 30분 창과 다른 의도다 */
    static final Duration DEDUP_WINDOW = Duration.ofSeconds(30);

    private final VisitEventRepository visitEventRepository;
    private final DailyMetricRollupRepository rollupRepository;
    private final VisitClassifier classifier;
    private final GeoIpService geoIpService;
    private final DailyIpHasher ipHasher;

    /**
     * 비콘 1건 판정·기록. 세션 쿠키 갱신은 컨트롤러가 응답으로 수행하므로 30초 중복으로
     * 이벤트를 건너뛰어도 반환값에는 세션 UUID가 항상 담긴다.
     */
    @Transactional
    public BeaconOutcome record(VisitBeaconRequest request, String visitorCookie, String sessionCookie,
                                String userAgent, String acceptLanguage, String clientIp) {
        Instant now = Instant.now();
        LocalDate dateKst = LocalDate.now(KST);
        VisitClassifier.PathInfo pathInfo = classifier.pathGroup(request.path());
        String referrerDomain = classifier.referrerDomain(request.referrer());

        if (classifier.isBot(userAgent)) {
            // 봇도 기록은 한다(별도 카운트 — 크롤러 트래픽은 서버 운영 정보) — 모든 통계 지표에서는 제외
            visitEventRepository.save(new VisitEvent(
                    now, dateKst, pathInfo.pathGroup(), pathInfo.shareToken(), referrerDomain, null,
                    classifier.browser(userAgent), classifier.os(userAgent), classifier.device(userAgent),
                    classifier.lang(acceptLanguage),
                    null, null, UUID.randomUUID(), false, false, false, true));
            rollupRepository.increment(dateKst, "bot", "all", 1);
            return BeaconOutcome.ofBot();
        }

        UUID visitorUuid = parseUuid(visitorCookie);
        boolean cookiePresent = visitorUuid != null;
        if (!cookiePresent) {
            visitorUuid = UUID.randomUUID();
        }
        UUID sessionUuid = parseUuid(sessionCookie);
        boolean sessionNew = sessionUuid == null;
        if (sessionNew) {
            sessionUuid = UUID.randomUUID();
        }

        // 쿠키가 있으면 UUID로, 없으면 일일 IP 해시로 직전 이벤트를 본다(같은 판정 질의 공용)
        String ipHash = cookiePresent ? null : ipHasher.hash(clientIp == null ? "" : clientIp, dateKst);
        Optional<VisitEvent> last = cookiePresent
                ? visitEventRepository.findFirstByVisitorUuidOrderByOccurredAtDescIdDesc(visitorUuid)
                : visitEventRepository.findFirstByVisitorIpHashOrderByOccurredAtDescIdDesc(ipHash);

        boolean dayFirst = last.isEmpty() || !last.get().getDateKst().equals(dateKst);
        boolean visitorNew = !cookiePresent && last.isEmpty();
        boolean duplicate = last.isPresent()
                && last.get().getPathGroup().equals(pathInfo.pathGroup())
                && !last.get().getOccurredAt().isBefore(now.minus(DEDUP_WINDOW));

        if (duplicate) {
            // 이벤트는 건너뛰되 세션 쿠키 갱신은 응답으로 수행한다(Section 4.2)
            return new BeaconOutcome(false, visitorUuid, !cookiePresent, sessionUuid);
        }

        String country = geoIpService.country(clientIp);
        String browser = classifier.browser(userAgent);
        String os = classifier.os(userAgent);
        String device = classifier.device(userAgent);
        String lang = classifier.lang(acceptLanguage);

        visitEventRepository.save(new VisitEvent(
                now, dateKst, pathInfo.pathGroup(), pathInfo.shareToken(), referrerDomain, country,
                browser, os, device, lang,
                visitorUuid, ipHash, sessionUuid, dayFirst, visitorNew, sessionNew, false));

        rollupRepository.increment(dateKst, "pv", "all", 1);
        if (sessionNew) {
            rollupRepository.increment(dateKst, "session", "all", 1);
        }
        if (visitorNew) {
            rollupRepository.increment(dateKst, "visitor_new", "all", 1);
        } else if (dayFirst && cookiePresent) {
            rollupRepository.increment(dateKst, "visitor_returning", "all", 1);
        } else if (dayFirst) {
            // 쿠키를 받지 못한 재방문 — 신규/재방문 미분류 근사(Section 3)
            rollupRepository.increment(dateKst, "visitor_estimated", "all", 1);
        }
        if (pathInfo.shareToken() != null) {
            rollupRepository.increment(dateKst, "share", pathInfo.shareToken(), 1);
        }
        rollupRepository.increment(dateKst, "country", country == null ? "unknown" : country, 1);
        rollupRepository.increment(dateKst, "browser", browser, 1);
        rollupRepository.increment(dateKst, "os", os, 1);
        rollupRepository.increment(dateKst, "device", device, 1);
        rollupRepository.increment(dateKst, "lang", lang, 1);
        rollupRepository.increment(dateKst, "referrer", referrerDomain, 1);
        rollupRepository.increment(dateKst, "page", pathInfo.pathGroup(), 1);

        return new BeaconOutcome(false, visitorUuid, !cookiePresent, sessionUuid);
    }

    private static UUID parseUuid(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cookie.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
