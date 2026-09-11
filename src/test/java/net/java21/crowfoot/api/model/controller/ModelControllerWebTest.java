package net.java21.crowfoot.api.model.controller;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.ModelSummaryResponse;
import net.java21.crowfoot.api.model.service.ModelService;
import net.java21.crowfoot.common.ListApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ERD 문서 API 웹 계층 테스트 (08-core/02-model.md) — 경로·201 Location·검증. */
@WebMvcTest(ModelController.class)
class ModelControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelService modelService;

    @Test
    @DisplayName("생성은 201 + Location(외부 URI) + databaseType·캔버스 크기를 응답한다")
    void createReturns201WithLocation() throws Exception {
        // given
        given(modelService.create(eq(7L), eq(77L), eq(new net.java21.crowfoot.api.model.dto.CreateModelRequest(
                "주문 서비스 ERD", null, "postgresql", 1920, 1080))))
                .willReturn(new ModelResponse("501", "77", "주문 서비스 ERD", null, "postgresql",
                        1920, 1080, "{\"tables\":[],\"relationships\":[]}", 0,
                        new UserRefResponse("7", "marco"),
                        Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z")));

        // when & then
        mockMvc.perform(post("/core/workspaces/77/models").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"주문 서비스 ERD\",\"databaseType\":\"postgresql\",\"canvasWidth\":1920,\"canvasHeight\":1080}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/workspaces/77/models/501"))
                .andExpect(jsonPath("$.response.modelId").value("501"))
                .andExpect(jsonPath("$.response.databaseType").value("postgresql"))
                .andExpect(jsonPath("$.response.canvasWidth").value(1920))
                .andExpect(jsonPath("$.response.canvasHeight").value(1080))
                .andExpect(jsonPath("$.response.content").exists());
    }

    @Test
    @DisplayName("생성 요청의 databaseType 누락은 400 INVALID_REQUEST이다")
    void createRejectsMissingDatabaseType() throws Exception {
        mockMvc.perform(post("/core/workspaces/77/models").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"주문 ERD\",\"canvasWidth\":1920,\"canvasHeight\":1080}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("목록은 페이징 포맷으로 요약(content 제외)을 응답한다 — keyword·page 바인딩")
    void listReturnsPagedSummaries() throws Exception {
        // given
        given(modelService.list(7L, 77L, "주문", 1, 20)).willReturn(ListApiResponse.paged(
                List.of(new ModelSummaryResponse("501", "77", "주문 서비스 ERD", null, "postgresql",
                        1920, 1080, 0, new UserRefResponse("7", "marco"),
                        Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"))),
                1, 20, 1));

        // when & then
        mockMvc.perform(get("/core/workspaces/77/models")
                        .header("X-USER-ID", "7")
                        .param("keyword", "주문")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].modelId").value("501"))
                .andExpect(jsonPath("$.responses[0].databaseType").value("postgresql"))
                .andExpect(jsonPath("$.responses[0].content").doesNotExist());
    }

    @Test
    @DisplayName("상세는 content를 포함한 전체 필드를 응답한다 (1.3)")
    void detailReturnsFullModel() throws Exception {
        // given
        given(modelService.detail(7L, 77L, 501L)).willReturn(new ModelResponse("501", "77", "주문 서비스 ERD",
                "설명", "postgresql", 1920, 1080, "{\"tables\":[],\"relationships\":[]}", 3,
                new UserRefResponse("7", "marco"),
                Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z")));

        // when & then
        mockMvc.perform(get("/core/workspaces/77/models/501").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.modelId").value("501"))
                .andExpect(jsonPath("$.response.databaseType").value("postgresql"))
                .andExpect(jsonPath("$.response.version").value(3))
                .andExpect(jsonPath("$.response.content").value("{\"tables\":[],\"relationships\":[]}"));
    }
}
