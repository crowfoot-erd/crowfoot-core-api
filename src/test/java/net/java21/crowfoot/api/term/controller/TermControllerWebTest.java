package net.java21.crowfoot.api.term.controller;

import net.java21.crowfoot.api.term.dto.TermResponse;
import net.java21.crowfoot.api.term.dto.UpsertTermRequest;
import net.java21.crowfoot.api.term.service.TermService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 용어 사전 API 웹 계층 테스트 (08-core/01-workspace.md Section 4) — 경로·upsert 200·삭제 204·검증. */
@WebMvcTest(TermController.class)
class TermControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TermService termService;

    @Test
    @DisplayName("목록은 200 — 페이징 메타 없는 목록 형식을 응답한다(DBMS별 types 포함)")
    void listReturnsTerms() throws Exception {
        given(termService.list(eq(7L), eq(77L))).willReturn(List.of(
                new TermResponse("11", "77", "order", "주문", Map.of("mysql", "DECIMAL(15,2)"), Instant.parse("2026-09-23T00:00:00Z")),
                new TermResponse("12", "77", "user", "사용자", null, Instant.parse("2026-09-23T00:00:00Z"))));

        mockMvc.perform(get("/core/workspaces/77/terms")
                        .header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].termId").value("11"))
                .andExpect(jsonPath("$.responses[0].term").value("order"))
                .andExpect(jsonPath("$.responses[0].label").value("주문"))
                .andExpect(jsonPath("$.responses[0].types.mysql").value("DECIMAL(15,2)"))
                .andExpect(jsonPath("$.responses[1].term").value("user"))
                .andExpect(jsonPath("$.responses[1].label").value("사용자"))
                .andExpect(jsonPath("$.responses[1].types").isEmpty())
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    @Test
    @DisplayName("upsert는 항상 200 — 신규·수정 구분이 없다(자연키), DBMS별 types도 함께 받는다")
    void upsertReturns200() throws Exception {
        given(termService.upsert(eq(7L), eq(77L),
                        eq(new UpsertTermRequest("user", "사용자", Map.of("mysql", "VARCHAR(100)")))))
                .willReturn(new TermResponse("11", "77", "user", "사용자", Map.of("mysql", "VARCHAR(100)"), Instant.parse("2026-09-23T00:00:00Z")));

        mockMvc.perform(post("/core/workspaces/77/terms")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"term\":\"user\",\"label\":\"사용자\",\"types\":{\"mysql\":\"VARCHAR(100)\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.termId").value("11"))
                .andExpect(jsonPath("$.response.term").value("user"))
                .andExpect(jsonPath("$.response.label").value("사용자"))
                .andExpect(jsonPath("$.response.types.mysql").value("VARCHAR(100)"));
    }

    @Test
    @DisplayName("삭제는 204 본문 없음")
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/core/workspaces/77/terms/11")
                        .header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("term·label 누락은 400 INVALID_REQUEST이다(type은 선택)")
    void rejectsMissingFields() throws Exception {
        mockMvc.perform(post("/core/workspaces/77/terms")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"term\":\"user\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/core/workspaces/77/terms")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"label\":\"사용자\"}"))
                .andExpect(status().isBadRequest());
    }
}
