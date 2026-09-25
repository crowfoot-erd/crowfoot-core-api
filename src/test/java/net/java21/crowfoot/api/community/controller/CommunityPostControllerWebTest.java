package net.java21.crowfoot.api.community.controller;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.community.dto.CommunityPostDetailResponse;
import net.java21.crowfoot.api.community.dto.CommunityPostSummaryResponse;
import net.java21.crowfoot.api.community.dto.CommunityRecentPostResponse;
import net.java21.crowfoot.api.community.dto.CreateCommunityPostRequest;
import net.java21.crowfoot.api.community.dto.UpdateCommunityPostRequest;
import net.java21.crowfoot.api.community.service.CommunityPostService;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import net.java21.crowfoot.common.i18n.LocalizedText;
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

/** 커뮤니티 게시글 API 웹 계층 테스트 (08-core/08-community.md) — 경로·201 Location·검증·최근글·?lang= 해석 */
@WebMvcTest(CommunityPostController.class)
class CommunityPostControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommunityPostService communityPostService;

    @Test
    @DisplayName("생성은 201 + Location(외부 URI) + 상세 본문을 응답한다 — 문자열 제목·본문은 {ko:값}으로 바인딩")
    void createReturns201WithExternalLocation() throws Exception {
        // given
        given(communityPostService.create(7L, new CreateCommunityPostRequest(
                "FEEDBACK", LocalizedText.of("검색 필터 개선 제안"), LocalizedText.of("본문"))))
                .willReturn(detail());

        // when & then
        mockMvc.perform(post("/core/community/posts").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"board\":\"FEEDBACK\",\"title\":\"검색 필터 개선 제안\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/community/posts/41"))
                .andExpect(jsonPath("$.response.postId").value("41"))
                .andExpect(jsonPath("$.response.board").value("FEEDBACK"))
                .andExpect(jsonPath("$.response.availableLangs[0]").value("ko"))
                .andExpect(jsonPath("$.response.content").exists());
    }

    @Test
    @DisplayName("생성은 4개 언어 객체 본문도 받는다 — 쓰기 다형(§2.1)")
    void createAcceptsLocalizedObject() throws Exception {
        // given
        given(communityPostService.create(7L, new CreateCommunityPostRequest(
                "RELEASE_NOTE",
                LocalizedText.of(java.util.Map.of("ko", "v1.16", "en", "v1.16", "ja", "v1.16", "zh", "v1.16")),
                LocalizedText.of(java.util.Map.of("ko", "본문", "en", "Body", "ja", "本文", "zh", "正文")))))
                .willReturn(detail());

        // when & then
        mockMvc.perform(post("/core/community/posts").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"board\":\"RELEASE_NOTE\",\"title\":{\"ko\":\"v1.16\",\"en\":\"v1.16\",\"ja\":\"v1.16\",\"zh\":\"v1.16\"},"
                                + "\"content\":{\"ko\":\"본문\",\"en\":\"Body\",\"ja\":\"本文\",\"zh\":\"正文\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.postId").value("41"));
    }

    @Test
    @DisplayName("생성 요청의 board 누락·무효 값은 400 검증 실패(errors[])이다 — 빈 다국어 제목은 서비스가 400(커스텀 타입)")
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
    }

    @Test
    @DisplayName("목록은 board·keyword·page 바인딩으로 페이징 포맷을 응답한다 — content 없음")
    void listReturnsPagedSummaries() throws Exception {
        // given
        given(communityPostService.list("FEEDBACK", "검색", 1, 20, null)).willReturn(ListApiResponse.paged(
                List.of(new CommunityPostSummaryResponse("41", "FEEDBACK", "검색 필터 개선 제안",
                        List.of("ko", "en"), new UserRefResponse("7", "marco"), 3,
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
                .andExpect(jsonPath("$.responses[0].availableLangs[1]").value("en"))
                .andExpect(jsonPath("$.responses[0].commentCount").value(3))
                .andExpect(jsonPath("$.responses[0].author.name").value("marco"))
                .andExpect(jsonPath("$.responses[0].content").doesNotExist());
    }

    @Test
    @DisplayName("목록의 ?lang=은 제목 해석 언어로 서비스에 전달된다")
    void listBindsLangParam() throws Exception {
        // given
        given(communityPostService.list("FEEDBACK", null, 1, 20, "ja")).willReturn(ListApiResponse.paged(
                List.of(new CommunityPostSummaryResponse("41", "FEEDBACK", "検索フィルター改善の提案",
                        List.of("ko", "ja"), new UserRefResponse("7", "marco"), 0,
                        Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"))),
                1, 20, 1));

        // when & then
        mockMvc.perform(get("/core/community/posts")
                        .header("X-USER-ID", "7")
                        .param("board", "FEEDBACK")
                        .param("page", "1")
                        .param("size", "20")
                        .param("lang", "ja"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].title").value("検索フィルター改善の提案"));
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
        given(communityPostService.recent(5, null)).willReturn(ListApiResponse.of(List.of(
                new CommunityRecentPostResponse("41", "FEEDBACK", "제안", List.of("ko"),
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
    @DisplayName("상세는 ?lang=을 해석 언어로 전달하고 availableLangs를 함께 내려준다")
    void detailBindsLangParam() throws Exception {
        // given
        given(communityPostService.detail(41L, "zh")).willReturn(detail());

        // when & then
        mockMvc.perform(get("/core/community/posts/41").header("X-USER-ID", "7").param("lang", "zh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.postId").value("41"))
                .andExpect(jsonPath("$.response.availableLangs[0]").value("ko"));
    }

    @Test
    @DisplayName("상세 대상이 없으면 404 공통 실패 포맷(헤더 resultCode)이다")
    void notFoundReturnsCommonFailureFormat() throws Exception {
        // given
        given(communityPostService.detail(99L, null)).willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));

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
                new UpdateCommunityPostRequest(LocalizedText.of("바뀐 제목"), LocalizedText.of("바뀐 본문"))))
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
        return new CommunityPostDetailResponse("41", "FEEDBACK", "검색 필터 개선 제안", List.of("ko"), "본문",
                new UserRefResponse("7", "marco"),
                Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"));
    }
}
