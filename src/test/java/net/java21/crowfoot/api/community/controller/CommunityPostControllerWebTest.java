package net.java21.crowfoot.api.community.controller;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.community.dto.CommunityPostDetailResponse;
import net.java21.crowfoot.api.community.dto.CommunityPostSummaryResponse;
import net.java21.crowfoot.api.community.dto.CommunityRecentPostResponse;
import net.java21.crowfoot.api.community.service.CommunityPostService;
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

import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 커뮤니티 게시글 API 웹 계층 테스트 (08-core/08-community.md) — 경로·201 Location·검증·최근글 */
@WebMvcTest(CommunityPostController.class)
class CommunityPostControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommunityPostService communityPostService;

    @Test
    @DisplayName("생성은 201 + Location(외부 URI) + 상세 본문을 응답한다")
    void createReturns201WithExternalLocation() throws Exception {
        // given
        given(communityPostService.create(7L, new net.java21.crowfoot.api.community.dto.CreateCommunityPostRequest(
                "FEEDBACK", "검색 필터 개선 제안", "본문")))
                .willReturn(detail());

        // when & then
        mockMvc.perform(post("/core/community/posts").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"board\":\"FEEDBACK\",\"title\":\"검색 필터 개선 제안\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/community/posts/41"))
                .andExpect(jsonPath("$.response.postId").value("41"))
                .andExpect(jsonPath("$.response.board").value("FEEDBACK"))
                .andExpect(jsonPath("$.response.content").exists());
    }

    @Test
    @DisplayName("생성 요청의 board 누락·무효 값·빈 제목은 400 검증 실패(errors[])이다")
    void createRejectsInvalidBody() throws Exception {
        // board 누락
        mockMvc.perform(post("/core/community/posts").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"제목\",\"content\":\"본문\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors").exists());
        // board 무효
        mockMvc.perform(post("/core/community/posts").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"board\":\"NOTICE\",\"title\":\"제목\",\"content\":\"본문\"}"))
                .andExpect(status().isBadRequest());
        // 빈 제목
        mockMvc.perform(post("/core/community/posts").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"board\":\"FEEDBACK\",\"title\":\"\",\"content\":\"본문\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("목록은 board·keyword·page 바인딩으로 페이징 포맷을 응답한다 — content 없음")
    void listReturnsPagedSummaries() throws Exception {
        // given
        given(communityPostService.list("FEEDBACK", "검색", 1, 20)).willReturn(ListApiResponse.paged(
                List.of(new CommunityPostSummaryResponse("41", "FEEDBACK", "검색 필터 개선 제안",
                        new UserRefResponse("7", "marco"), 3,
                        Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"))),
                1, 20, 1));

        // when & then
        mockMvc.perform(get("/core/community/posts")
                        .header("X-USER-ID", "7")
                        .param("board", "FEEDBACK")
                        .param("keyword", "검색")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.responses[0].postId").value("41"))
                .andExpect(jsonPath("$.responses[0].commentCount").value(3))
                .andExpect(jsonPath("$.responses[0].author.name").value("marco"))
                .andExpect(jsonPath("$.responses[0].content").doesNotExist());
    }

    @Test
    @DisplayName("목록의 board 누락은 400 INVALID_REQUEST이다")
    void listRejectsMissingBoard() throws Exception {
        mockMvc.perform(get("/core/community/posts").header("X-USER-ID", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("최근글은 limit 바인딩으로 페이징 없는 목록 포맷을 응답한다")
    void recentReturnsRecentList() throws Exception {
        // given
        given(communityPostService.recent(5)).willReturn(ListApiResponse.of(List.of(
                new CommunityRecentPostResponse("41", "FEEDBACK", "제안",
                        new UserRefResponse("7", "marco"), 3, Instant.parse("2026-09-17T00:00:00Z")))));

        // when & then
        mockMvc.perform(get("/core/community/posts/recent").header("X-USER-ID", "7").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].postId").value("41"))
                .andExpect(jsonPath("$.responses[0].board").value("FEEDBACK"))
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test
    @DisplayName("상세 대상이 없으면 404 공통 실패 포맷(헤더 resultCode)이다")
    void notFoundReturnsCommonFailureFormat() throws Exception {
        // given
        given(communityPostService.detail(99L)).willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/core/community/posts/99").header("X-USER-ID", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("COMMUNITY_POST_NOT_FOUND"))
                .andExpect(jsonPath("$.response").doesNotExist());
    }

    @Test
    @DisplayName("수정은 갱신된 상세를 응답한다")
    void patchReturnsUpdatedDetail() throws Exception {
        // given
        given(communityPostService.patch(7L, 41L,
                new net.java21.crowfoot.api.community.dto.UpdateCommunityPostRequest("바뀐 제목", "바뀐 본문")))
                .willReturn(detail());

        // when & then
        mockMvc.perform(patch("/core/community/posts/41").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"바뀐 제목\",\"content\":\"바뀐 본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.postId").value("41"));
    }

    @Test
    @DisplayName("삭제는 204 본문 없음이다")
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/core/community/posts/41").header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
    }

    private CommunityPostDetailResponse detail() {
        return new CommunityPostDetailResponse("41", "FEEDBACK", "검색 필터 개선 제안", "본문",
                new UserRefResponse("7", "marco"),
                Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"));
    }
}
