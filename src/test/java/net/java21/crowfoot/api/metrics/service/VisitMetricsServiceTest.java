package net.java21.crowfoot.api.metrics.service;

import net.java21.crowfoot.api.config.AppProperties;
import net.java21.crowfoot.api.metrics.domain.VisitEvent;
import net.java21.crowfoot.api.metrics.dto.VisitBeaconRequest;
import net.java21.crowfoot.api.metrics.repository.DailyMetricRollupRepository;
import net.java21.crowfoot.api.metrics.repository.VisitEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 비콘 판정 파이프라인 테스트 (08-core/10-metrics.md Section 4·5.2) — 리포지토리를 모킹해
 * 플래그 판정과 롤업 증분 차원을 검증한다. 증분 UPSERT(native)의 실물 PostgreSQL 검증은
 * 로컬 스택 8082 실측으로 수행한다.
 */
@ExtendWith(MockitoExtension.class)
class VisitMetricsServiceTest {

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36";
    private static final String GOOGLEBOT =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://google.com/bot.html)";

    @Mock
    private VisitEventRepository visitEventRepository;
    @Mock
    private DailyMetricRollupRepository rollupRepository;
    @Mock
    private GeoIpService geoIpService;

    private VisitMetricsService service;

    @BeforeEach
    void setUp() {
        // 분류기·해시기는 순수 계산이라 실물을 쓴다(봇 규칙·해시 결정성이 검증 대상이기도 하다)
        service = new VisitMetricsService(visitEventRepository, rollupRepository,
                new VisitClassifier(), geoIpService, new DailyIpHasher(new AppProperties(null, null, null, null)));
    }

    private VisitEvent pastEvent(LocalDate dateKst, String pathGroup, UUID visitorUuid, String ipHash) {
        return new VisitEvent(Instant.now().minusSeconds(600), dateKst, pathGroup, null, "direct", null,
                "chrome", "windows", "desktop", "ko", visitorUuid, ipHash, UUID.randomUUID(),
                false, false, false, false);
    }

    @Test
    @DisplayName("신규 방문자(쿠키 없음·흔적 없음) — visitor_new 증분, 발급 UUID를 행에도 남긴다, 쿠키 2종 발급")
    void newVisitor() {
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        given(visitEventRepository.findFirstByVisitorIpHashOrderByOccurredAtDescIdDesc(anyString()))
                .willReturn(Optional.empty());

        BeaconOutcome outcome = service.record(
                new VisitBeaconRequest("/workspaces", null), null, null, CHROME, "ko-KR", "203.0.113.9");

        assertThat(outcome.bot()).isFalse();
        assertThat(outcome.issueVisitorCookie()).isTrue();
        assertThat(outcome.visitorUuid()).isNotNull();
        assertThat(outcome.sessionUuid()).isNotNull();

        ArgumentCaptor<VisitEvent> captor = ArgumentCaptor.forClass(VisitEvent.class);
        verify(visitEventRepository).save(captor.capture());
        VisitEvent saved = captor.getValue();
        assertThat(saved.getVisitorUuid()).isEqualTo(outcome.visitorUuid());  // 발급값 저장 — 다음날 UUID 경로
        assertThat(saved.getVisitorIpHash()).isNotNull();                     // 일일 솔트 해시 저장
        assertThat(saved.isVisitorNew()).isTrue();
        assertThat(saved.isDayFirst()).isTrue();
        assertThat(saved.isSessionNew()).isTrue();
        assertThat(saved.isBot()).isFalse();

        verify(rollupRepository).increment(today, "pv", "all", 1);
        verify(rollupRepository).increment(today, "session", "all", 1);
        verify(rollupRepository).increment(today, "visitor_new", "all", 1);
        verify(rollupRepository).increment(today, "page", "/workspaces", 1);
        verify(rollupRepository).increment(today, "country", "unknown", 1);  // GeoIP 모킹 null → unknown 키
        verify(rollupRepository, never()).increment(any(), eq("visitor_returning"), anyString(), anyLong());
        verify(rollupRepository, never()).increment(any(), eq("visitor_estimated"), anyString(), anyLong());
    }

    @Test
    @DisplayName("쿠키 보유 같은 날 재방문(다른 경로) — pv만 증분, 세션·방문자 지표는 세지 않는다")
    void sameDayReturningVisitorWithCookie() {
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        UUID visitorUuid = UUID.randomUUID();
        UUID sessionUuid = UUID.randomUUID();
        given(visitEventRepository.findFirstByVisitorUuidOrderByOccurredAtDescIdDesc(visitorUuid))
                .willReturn(Optional.of(pastEvent(today, "/login", visitorUuid, null)));

        BeaconOutcome outcome = service.record(
                new VisitBeaconRequest("/workspaces", null), visitorUuid.toString(), sessionUuid.toString(),
                CHROME, "ko-KR", "203.0.113.9");

        assertThat(outcome.issueVisitorCookie()).isFalse();   // 쿠키 있음 — 재발급 없음
        assertThat(outcome.visitorUuid()).isEqualTo(visitorUuid);
        assertThat(outcome.sessionUuid()).isEqualTo(sessionUuid);

        verify(visitEventRepository).save(any(VisitEvent.class));
        verify(rollupRepository).increment(today, "pv", "all", 1);
        verify(rollupRepository, never()).increment(any(), eq("session"), anyString(), anyLong());
        verify(rollupRepository, never()).increment(any(), eq("visitor_new"), anyString(), anyLong());
        verify(rollupRepository, never()).increment(any(), eq("visitor_returning"), anyString(), anyLong());
        verify(rollupRepository, never()).increment(any(), eq("visitor_estimated"), anyString(), anyLong());
    }

    @Test
    @DisplayName("쿠키 보유 다른 날 첫 방문 — visitor_returning 증분")
    void nextDayReturningVisitorWithCookie() {
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        UUID visitorUuid = UUID.randomUUID();
        given(visitEventRepository.findFirstByVisitorUuidOrderByOccurredAtDescIdDesc(visitorUuid))
                .willReturn(Optional.of(pastEvent(today.minusDays(1), "/login", visitorUuid, null)));

        service.record(new VisitBeaconRequest("/workspaces", null), visitorUuid.toString(), null,
                CHROME, "ko-KR", "203.0.113.9");

        verify(rollupRepository).increment(today, "pv", "all", 1);
        verify(rollupRepository).increment(today, "session", "all", 1);       // 세션 쿠키 없음 → 새 세션
        verify(rollupRepository).increment(today, "visitor_returning", "all", 1);
        verify(rollupRepository, never()).increment(any(), eq("visitor_new"), anyString(), anyLong());
        verify(rollupRepository, never()).increment(any(), eq("visitor_estimated"), anyString(), anyLong());
    }

    @Test
    @DisplayName("쿠키 거부 재방문(어제 IP 해시 흔적) — visitor_estimated 증분, IP 해시 경로로 식별")
    void cookielessRepeatVisitorIsEstimated() {
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        given(visitEventRepository.findFirstByVisitorIpHashOrderByOccurredAtDescIdDesc(anyString()))
                .willReturn(Optional.of(pastEvent(today.minusDays(1), "/login", UUID.randomUUID(), "hash123")));

        BeaconOutcome outcome = service.record(
                new VisitBeaconRequest("/workspaces", null), null, null, CHROME, "ko-KR", "203.0.113.9");

        assertThat(outcome.issueVisitorCookie()).isTrue();    // 그래도 쿠키는 계속 시도한다
        ArgumentCaptor<VisitEvent> captor = ArgumentCaptor.forClass(VisitEvent.class);
        verify(visitEventRepository).save(captor.capture());
        assertThat(captor.getValue().isVisitorNew()).isFalse();
        assertThat(captor.getValue().isDayFirst()).isTrue();

        verify(rollupRepository).increment(today, "visitor_estimated", "all", 1);
        verify(rollupRepository, never()).increment(any(), eq("visitor_new"), anyString(), anyLong());
        verify(rollupRepository, never()).increment(any(), eq("visitor_returning"), anyString(), anyLong());
    }

    @Test
    @DisplayName("30초 중복(같은 경로) — 이벤트·롤업 생략, 세션 UUID는 응답용으로 돌아온다")
    void duplicateWithinWindowIsSkipped() {
        UUID visitorUuid = UUID.randomUUID();
        UUID sessionUuid = UUID.randomUUID();
        given(visitEventRepository.findFirstByVisitorUuidOrderByOccurredAtDescIdDesc(visitorUuid))
                .willReturn(Optional.of(new VisitEvent(Instant.now().minusSeconds(10),
                        LocalDate.now(VisitMetricsService.KST), "/workspaces", null, "direct", null,
                        "chrome", "windows", "desktop", "ko", visitorUuid, null, UUID.randomUUID(),
                        false, false, false, false)));

        BeaconOutcome outcome = service.record(
                new VisitBeaconRequest("/workspaces", null), visitorUuid.toString(), sessionUuid.toString(),
                CHROME, "ko-KR", "203.0.113.9");

        assertThat(outcome.bot()).isFalse();
        assertThat(outcome.sessionUuid()).isEqualTo(sessionUuid);
        assertThat(outcome.issueVisitorCookie()).isFalse();
        verify(visitEventRepository, never()).save(any());
        verify(rollupRepository, never()).increment(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("봇 — bot/all 증분만, 지표(pv·세션·방문자)와 쿠키 발급에서는 완전히 제외")
    void botIsRecordedSeparately() {
        BeaconOutcome outcome = service.record(
                new VisitBeaconRequest("/", null), null, null, GOOGLEBOT, null, "203.0.113.9");

        assertThat(outcome.bot()).isTrue();
        assertThat(outcome.visitorUuid()).isNull();
        assertThat(outcome.sessionUuid()).isNull();
        assertThat(outcome.issueVisitorCookie()).isFalse();

        ArgumentCaptor<VisitEvent> captor = ArgumentCaptor.forClass(VisitEvent.class);
        verify(visitEventRepository).save(captor.capture());
        assertThat(captor.getValue().isBot()).isTrue();
        assertThat(captor.getValue().getCountry()).isNull();

        verify(rollupRepository, times(1)).increment(any(), eq("bot"), eq("all"), eq(1L));
        verify(rollupRepository, never()).increment(any(), eq("pv"), anyString(), anyLong());
        verify(visitEventRepository, never()).findFirstByVisitorUuidOrderByOccurredAtDescIdDesc(any());
        verify(visitEventRepository, never()).findFirstByVisitorIpHashOrderByOccurredAtDescIdDesc(anyString());
    }

    @Test
    @DisplayName("공유 뷰어 방문 — share/{token} 차원이 함께 증분된다")
    void shareVisitIncrementsShareDimension() {
        LocalDate today = LocalDate.now(VisitMetricsService.KST);
        given(visitEventRepository.findFirstByVisitorIpHashOrderByOccurredAtDescIdDesc(anyString()))
                .willReturn(Optional.empty());

        service.record(new VisitBeaconRequest("/share/tok123", "https://www.google.com/search?q=erd"),
                null, null, CHROME, "ko-KR", "203.0.113.9");

        verify(rollupRepository).increment(today, "share", "tok123", 1);
        verify(rollupRepository).increment(today, "page", "/share/*", 1);
        verify(rollupRepository).increment(today, "referrer", "google.com", 1);
    }
}
