package net.java21.crowfoot.api.term.controller;

import net.java21.crowfoot.api.term.dto.SystemTermResponse;
import net.java21.crowfoot.api.term.dto.UpsertSystemTermRequest;
import net.java21.crowfoot.api.term.service.SystemTermService;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
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

/** 시스템 사전 API 웹 계층 테스트 (08-core/01-workspace.md Section 4.5) — 경로·labels 맵 응답·검증·401. */
@WebMvcTest(SystemTermController.class)
class SystemTermControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemTermService systemTermService;

    @Test
    @DisplayName("사용자 목록은 200 — 페이징 메타와 labels 맵을 응답한다")
    void listReturnsLabelsMap() throws Exception {
        given(systemTermService.list(null, null, null, null)).willReturn(ListApiResponse.paged(List.of(
                new SystemTermResponse("21", "email", Map.of("ko", "이메일", "en", "Email"),
                        Map.of("mysql", "VARCHAR(100)"), Instant.parse("2026-09-24T00:00:00Z")),
                new SystemTermResponse("22", "user", Map.of("ko", "사용자"),
                        null, Instant.parse("2026-09-24T00:00:00Z"))), 1, 20, 2));

        mockMvc.perform(get("/core/system-terms")
                        .header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].termId").value("21"))
                .andExpect(jsonPath("$.responses[0].term").value("email"))
                .andExpect(jsonPath("$.responses[0].labels.ko").value("이메일"))
                .andExpect(jsonPath("$.responses[0].labels.en").value("Email"))
                .andExpect(jsonPath("$.responses[0].types.mysql").value("VARCHAR(100)"))
                .andExpect(jsonPath("$.responses[1].labels.ko").value("사용자"))
                .andExpect(jsonPath("$.responses[1].types").isEmpty());
    }

    @Test
    @DisplayName("사용자 목록 — page·size·letter·keyword 쿼리 파라미터를 서비스에 전달한다")
    void listPassesPagingAndFilterParams() throws Exception {
        given(systemTermService.list(2, 50, "e", "이메일"))
                .willReturn(ListApiResponse.paged(List.of(), 2, 50, 60));

        mockMvc.perform(get("/core/system-terms")
                        .header("X-USER-ID", "7")
                        .param("page", "2")
                        .param("size", "50")
                        .param("letter", "e")
                        .param("keyword", "이메일"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.totalCount").value(60));
    }

    @Test
    @DisplayName("관리 목록은 200 — 같은 페이징 형식을 관리 경로로도 내린다")
    void adminListReturnsTerms() throws Exception {
        given(systemTermService.adminList(2L, null, null, null, null))
                .willReturn(ListApiResponse.paged(List.of(), 1, 20, 0));

        mockMvc.perform(get("/core/admin/system-terms")
                        .header("X-USER-ID", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    @DisplayName("등록(upsert)은 항상 200 — labels 맵과 DBMS별 types 맵을 받는다")
    void upsertReturns200() throws Exception {
        given(systemTermService.upsert(eq(2L), eq(new UpsertSystemTermRequest(
                        "email", Map.of("ko", "이메일"), Map.of("mysql", "VARCHAR(100)")))))
                .willReturn(new SystemTermResponse("21", "email", Map.of("ko", "이메일"),
                        Map.of("mysql", "VARCHAR(100)"), Instant.parse("2026-09-24T00:00:00Z")));

        mockMvc.perform(post("/core/admin/system-terms")
                        .header("X-USER-ID", "2")
                        .contentType(APPLICATION_JSON)
                        .content("{\"term\":\"email\",\"labels\":{\"ko\":\"이메일\"},\"types\":{\"mysql\":\"VARCHAR(100)\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.termId").value("21"))
                .andExpect(jsonPath("$.response.term").value("email"))
                .andExpect(jsonPath("$.response.labels.ko").value("이메일"))
                .andExpect(jsonPath("$.response.types.mysql").value("VARCHAR(100)"));
    }

    @Test
    @DisplayName("labels 누락·빈 맵은 400 INVALID_REQUEST다(빈 맵은 서비스 검증)")
    void rejectsInvalidLabels() throws Exception {
        // labels 필드 자체가 없으면 @NotNull 위반
        mockMvc.perform(post("/core/admin/system-terms")
                        .header("X-USER-ID", "2")
                        .contentType(APPLICATION_JSON)
                        .content("{\"term\":\"email\"}"))
                .andExpect(status().isBadRequest());

        // 빈 맵은 서비스 검증(1개 언어 이상)으로 400
        given(systemTermService.upsert(eq(2L), eq(new UpsertSystemTermRequest("email", Map.of(), null))))
                .willThrow(new BusinessException(ErrorCode.INVALID_REQUEST,
                        "라벨은 1~8개 언어로 등록해야 합니다"));
        mockMvc.perform(post("/core/admin/system-terms")
                        .header("X-USER-ID", "2")
                        .contentType(APPLICATION_JSON)
                        .content("{\"term\":\"email\",\"labels\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("삭제는 204 본문 없음")
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/core/admin/system-terms/21")
                        .header("X-USER-ID", "2"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("X-USER-ID 헤더가 없으면 401 — 시스템 사전도 인증이 필요하다")
    void missingUserIdHeaderIsUnauthorized() throws Exception {
        mockMvc.perform(get("/core/system-terms"))
                .andExpect(status().isUnauthorized());
    }
}
