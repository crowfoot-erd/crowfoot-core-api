package net.java21.crowfoot.api.metrics.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최소 수집 원칙 점검 (08-core/10-metrics.md Section 2·5.1) — visit_events 스키마(엔티티)에
 * IP 원문·UA 원문·경로 원문 컬럼이 존재하지 않음을 단언한다. 새 컬럼을 추가할 때 이 테스트가
 * 수집 범위 확장을 강제로 검토하게 한다.
 */
class VisitEventPrivacyTest {

    @Test
    @DisplayName("엔티티 필드는 문서화된 최소 집합과 정확히 일치한다 — IP·UA·경로 원문 부재")
    void fieldsAreLimitedToDocumentedMinimalSet() {
        Set<String> fields = Arrays.stream(VisitEvent.class.getDeclaredFields())
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder(
                "id", "occurredAt", "dateKst",
                "pathGroup", "shareToken", "referrerDomain", "country",
                "browser", "os", "device", "lang",
                "visitorUuid", "visitorIpHash", "sessionUuid",
                "isDayFirst", "isVisitorNew", "isSessionNew", "isBot");
    }

    @Test
    @DisplayName("IP·UA·요청 경로 원문을 담는 필드명은 어떤 형태로도 추가될 수 없다")
    void noRawIdentifierColumns() {
        Set<String> forbidden = Set.of("ip", "ipAddress", "clientIp", "userAgent", "ua",
                "path", "rawPath", "requestPath", "uri", "url", "queryString");
        Set<String> fields = Arrays.stream(VisitEvent.class.getDeclaredFields())
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        assertThat(fields).doesNotContainAnyElementsOf(forbidden);
    }
}
