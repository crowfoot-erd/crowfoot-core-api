package net.java21.crowfoot.api.metrics.controller;

import net.java21.crowfoot.api.metrics.dto.VisitBeaconRequest;
import net.java21.crowfoot.api.metrics.service.BeaconOutcome;
import net.java21.crowfoot.api.metrics.service.VisitMetricsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 비콘 API 웹 계층 테스트 (08-core/10-metrics.md Section 3) — 무인증 204·쿠키 속성·X-Forwarded-For. */
@WebMvcTest(MetricsController.class)
class MetricsControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VisitMetricsService visitMetricsService;

    @Test
    @DisplayName("신규 방문자 비콘은 X-USER-ID 없이도(XUserIdFilter 제외 경로) 204 + 방문자·세션 쿠키 2종")
    void newVisitorGetsBothCookies() throws Exception {
        UUID visitorUuid = UUID.randomUUID();
        given(visitMetricsService.record(any(VisitBeaconRequest.class), isNull(), isNull(), any(), any(), any()))
                .willReturn(new BeaconOutcome(false, visitorUuid, true, UUID.randomUUID()));

        MvcResult result = mockMvc.perform(post("/core/metrics/visit")
                        .contentType(APPLICATION_JSON)
                        .content("{\"path\":\"/workspaces\"}"))
                .andExpect(status().isNoContent())
                .andReturn();

        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2);
        String visitor = cookies.stream().filter(c -> c.startsWith("crowfoot_visitor=")).findFirst().orElseThrow();
        String session = cookies.stream().filter(c -> c.startsWith("crowfoot_stats_session=")).findFirst().orElseThrow();
        assertThat(visitor).contains(visitorUuid.toString(), "Path=/api/v1/core/metrics",
                "Max-Age=34560000");
        assertThat(session).contains("Path=/api/v1/core/metrics", "Max-Age=1800");
        // 두 쿠키 모두 HttpOnly·Secure·SameSite=Strict (Section 3)
        assertThat(visitor).contains("HttpOnly", "Secure", "SameSite=Strict");
        assertThat(session).contains("HttpOnly", "Secure", "SameSite=Strict");
    }

    @Test
    @DisplayName("기존 방문자 비콘 — 세션 쿠키(30분 슬라이딩)만 재발급한다")
    void returningVisitorGetsSessionCookieOnly() throws Exception {
        given(visitMetricsService.record(any(VisitBeaconRequest.class), any(), any(), any(), any(), any()))
                .willReturn(new BeaconOutcome(false, UUID.randomUUID(), false, UUID.randomUUID()));

        MvcResult result = mockMvc.perform(post("/core/metrics/visit")
                        .contentType(APPLICATION_JSON)
                        .content("{\"path\":\"/workspaces\"}"))
                .andExpect(status().isNoContent())
                .andReturn();

        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0)).startsWith("crowfoot_stats_session=").contains("Max-Age=1800");
    }

    @Test
    @DisplayName("봇 비콘 — 204 본문 없음, Set-Cookie 헤더 자체가 없다")
    void botGetsNoCookies() throws Exception {
        given(visitMetricsService.record(any(VisitBeaconRequest.class), isNull(), isNull(), any(), any(), any()))
                .willReturn(new BeaconOutcome(true, null, false, null));

        mockMvc.perform(post("/core/metrics/visit")
                        .contentType(APPLICATION_JSON)
                        .content("{\"path\":\"/\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("클라이언트 IP는 X-Forwarded-For 첫 홉으로 판정해 서비스에 넘긴다")
    void clientIpUsesFirstForwardedHop() throws Exception {
        given(visitMetricsService.record(any(VisitBeaconRequest.class), isNull(), isNull(), any(), any(), eq("1.2.3.4")))
                .willReturn(new BeaconOutcome(false, UUID.randomUUID(), false, UUID.randomUUID()));

        mockMvc.perform(post("/core/metrics/visit")
                        .contentType(APPLICATION_JSON)
                        .header("X-Forwarded-For", "1.2.3.4, 10.0.0.1")
                        .content("{\"path\":\"/\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("빈 path는 400 — 비콘 본문 검증")
    void emptyPathIsRejected() throws Exception {
        mockMvc.perform(post("/core/metrics/visit")
                        .contentType(APPLICATION_JSON)
                        .content("{\"path\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
