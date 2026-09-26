package net.java21.crowfoot.api.metrics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 방문 이벤트 원본 (08-core/10-metrics.md Section 5.1) — 1행 = 1건의 비콘(유효 또는 봇).
 * INSERT-only이고 90일 초과분은 일일 배치로 삭제된다(영구 통계는 daily_metric_rollups).
 *
 * <p>프라이버시(최소 수집): IP 원문·UA 원문·경로 원문 컬럼이 없다 — IP는 일일 솔트 해시
 * (visitorIpHash — 쿠키를 받지 않은 방문자의 식별 근사), UA는 browser/os/device 폐쇄 집합,
 * 경로는 pathGroup 정규화(shareToken은 공개 값이라 예외 보관).
 */
@Entity
@Table(name = "visit_events", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class VisitEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant occurredAt;

    /** 발생일(KST 고정) — 일별 집계·보존 기준 */
    private LocalDate dateKst;

    private String pathGroup;

    /** 공유 뷰어 방문의 문서 토큰 — 그 외 경로는 null */
    private String shareToken;

    private String referrerDomain;

    /** GeoIP 판정 ISO 3166-1 alpha-2 — 사설/실패는 null(unknown) */
    private String country;

    private String browser;

    private String os;

    private String device;

    private String lang;

    /** 이 이벤트에서 발급됐거나 수신한 방문자 UUID — 쿠키가 다음 요청에 살아 있으면 이 값으로 식별이 이어진다 */
    private UUID visitorUuid;

    /** 일일 솔트 IP 해시 — 방문자 쿠키를 받지 못한(거부·미지원) 방문자의 식별 근사. 원복 불가 */
    private String visitorIpHash;

    /** 세션 UUID(30분 슬라이딩) — 봇 행은 요청 밖 UUID(식별 의미 없음) */
    private UUID sessionUuid;

    private boolean isDayFirst;

    private boolean isVisitorNew;

    private boolean isSessionNew;

    private boolean isBot;

    public VisitEvent(Instant occurredAt, LocalDate dateKst, String pathGroup, String shareToken,
                      String referrerDomain, String country, String browser, String os, String device,
                      String lang, UUID visitorUuid, String visitorIpHash, UUID sessionUuid,
                      boolean isDayFirst, boolean isVisitorNew, boolean isSessionNew, boolean isBot) {
        this.occurredAt = occurredAt;
        this.dateKst = dateKst;
        this.pathGroup = pathGroup;
        this.shareToken = shareToken;
        this.referrerDomain = referrerDomain;
        this.country = country;
        this.browser = browser;
        this.os = os;
        this.device = device;
        this.lang = lang;
        this.visitorUuid = visitorUuid;
        this.visitorIpHash = visitorIpHash;
        this.sessionUuid = sessionUuid;
        this.isDayFirst = isDayFirst;
        this.isVisitorNew = isVisitorNew;
        this.isSessionNew = isSessionNew;
        this.isBot = isBot;
    }
}
