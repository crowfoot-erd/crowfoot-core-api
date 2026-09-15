package net.java21.crowfoot.api.model.controller;

import net.java21.crowfoot.api.model.dto.CreateShareRequest;
import net.java21.crowfoot.api.model.dto.GalleryShareResponse;
import net.java21.crowfoot.api.model.dto.ModelShareResponse;
import net.java21.crowfoot.api.model.dto.PublicShareResponse;
import net.java21.crowfoot.api.model.service.ShareService;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 문서 공유 링크 API 웹 계층 테스트 (08-core/02-model.md Section 1.10) — 경로·201 Location·공개 경로 무인증. */
@WebMvcTest(ModelShareController.class)
class ModelShareControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShareService shareService;

    @Test
    @DisplayName("발급은 201 + Location(관리 리소스) + 토큰을 응답한다")
    void createReturns201WithLocation() throws Exception {
        // given
        given(shareService.create(eq(7L), eq(77L), eq(501L), eq(new CreateShareRequest(null, null))))
                .willReturn(new ModelShareResponse("9", "Ab3xYz0123456789QrStUv", null, null,
                        Instant.parse("2026-09-15T00:00:00Z")));

        // when & then
        mockMvc.perform(post("/core/workspaces/77/models/501/shares").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/v1/core/workspaces/77/models/501/shares/9"))
                .andExpect(jsonPath("$.response.shareId").value("9"))
                .andExpect(jsonPath("$.response.shareToken").value("Ab3xYz0123456789QrStUv"))
                .andExpect(jsonPath("$.response.startsAt").doesNotExist())
                .andExpect(jsonPath("$.response.endsAt").doesNotExist());
    }

    @Test
    @DisplayName("목록은 최근 발급순 배열로 응답한다")
    void listReturnsShares() throws Exception {
        given(shareService.list(7L, 77L, 501L)).willReturn(List.of(
                new ModelShareResponse("9", "tok456",
                        Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"),
                        Instant.parse("2026-09-15T00:00:00Z"))));

        mockMvc.perform(get("/core/workspaces/77/models/501/shares").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].shareToken").value("tok456"))
                .andExpect(jsonPath("$.responses[0].startsAt").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("철회는 204 본문 없음이다")
    void revokeReturns204() throws Exception {
        mockMvc.perform(delete("/core/workspaces/77/models/501/shares/9").header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("공개 조회는 X-USER-ID 없이도 200으로 문서를 내려준다 — 토큰이 자격")
    void resolveIsPublicWithoutUserId() throws Exception {
        given(shareService.resolve("tok123")).willReturn(new PublicShareResponse(
                "주문 ERD", "설명", "postgresql", 3, "{\"tables\":[]}", null, null));

        mockMvc.perform(get("/core/shares/tok123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.modelName").value("주문 ERD"))
                .andExpect(jsonPath("$.response.databaseType").value("postgresql"))
                .andExpect(jsonPath("$.response.content").exists());
    }

    @Test
    @DisplayName("공개 조회는 기간 밖 토큰이면 410 SHARE_INACTIVE다")
    void resolveReturns410ForInactiveShare() throws Exception {
        willThrow(new BusinessException(ErrorCode.SHARE_INACTIVE))
                .given(shareService).resolve("expired");

        mockMvc.perform(get("/core/shares/expired"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.header.resultCode").value("SHARE_INACTIVE"));
    }

    @Test
    @DisplayName("공개 조회는 알 수 없는 토큰이면 404 SHARE_NOT_FOUND다")
    void resolveReturns404ForUnknownToken() throws Exception {
        willThrow(new BusinessException(ErrorCode.SHARE_NOT_FOUND))
                .given(shareService).resolve("nope");

        mockMvc.perform(get("/core/shares/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SHARE_NOT_FOUND"));
    }

    @Test
    @DisplayName("갤러리는 X-USER-ID 없이도 200으로 목록 포맷을 내려준다 — 본문 없이 메타만")
    void galleryIsPublicWithoutUserId() throws Exception {
        given(shareService.gallery()).willReturn(List.of(new GalleryShareResponse(
                "Ab3xYz0123456789QrStUv", "주문 ERD", "설명", "postgresql",
                Instant.parse("2026-09-16T09:00:00Z"), Instant.parse("2026-09-15T07:30:00Z"))));

        mockMvc.perform(get("/core/shares"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].shareToken").value("Ab3xYz0123456789QrStUv"))
                .andExpect(jsonPath("$.responses[0].modelName").value("주문 ERD"))
                .andExpect(jsonPath("$.responses[0].content").doesNotExist())
                .andExpect(jsonPath("$.totalCount").value(1));
    }
}
