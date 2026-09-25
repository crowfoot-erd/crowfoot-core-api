package net.java21.crowfoot.api.community.controller;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.community.dto.CommunityPostDetailResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 릴리스 노트 공개 조회 웹 계층 테스트 (08-core/08-community.md Section 3.11) — 무헤더 공개·존재 은닉·?lang= 해석 */
@WebMvcTest(CommunityReleaseNoteController.class)
class CommunityReleaseNoteControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommunityPostService communityPostService;

    @Test
    @DisplayName("최근 릴리스 노트는 X-USER-ID 없이도 200으로 목록을 내려준다 — 공개 경로")
    void recentIsPublicWithoutUserId() throws Exception {
        // given
        given(communityPostService.recentReleaseNotes(3, null)).willReturn(ListApiResponse.of(List.of(
                new CommunityRecentPostResponse("9", "RELEASE_NOTE", "v1.08 — 커뮤니티 게시판", List.of("ko"),
                        new UserRefResponse("1", "관리자"), 0, Instant.parse("2026-09-18T00:00:00Z")))));

        // when & then
        mockMvc.perform(get("/core/community/release-notes/recent").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].postId").value("9"))
                .andExpect(jsonPath("$.responses[0].board").value("RELEASE_NOTE"))
                .andExpect(jsonPath("$.responses[0].availableLangs[0]").value("ko"))
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test
    @DisplayName("limit을 생략하면 서비스에 null이 전달되어 기본값으로 조회된다")
    void recentDefaultsLimitToNull() throws Exception {
        // given
        given(communityPostService.recentReleaseNotes(null, null))
                .willReturn(ListApiResponse.of(List.of()));

        // when & then
        mockMvc.perform(get("/core/community/release-notes/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    @DisplayName("최근 릴리스 노트의 ?lang=은 제목 해석 언어로 서비스에 전달된다")
    void recentBindsLangParam() throws Exception {
        // given
        given(communityPostService.recentReleaseNotes(3, "ja")).willReturn(ListApiResponse.of(List.of(
                new CommunityRecentPostResponse("9", "RELEASE_NOTE", "v1.08 — コミュニティ掲示板",
                        List.of("ko", "ja"), new UserRefResponse("1", "관리자"), 0,
                        Instant.parse("2026-09-18T00:00:00Z")))));

        // when & then
        mockMvc.perform(get("/core/community/release-notes/recent").param("limit", "3").param("lang", "ja"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].title").value("v1.08 — コミュニティ掲示板"))
                .andExpect(jsonPath("$.responses[0].availableLangs[1]").value("ja"));
    }

    @Test
    @DisplayName("상세는 X-USER-ID 없이도 200으로 마크다운 원문을 내려준다 — 공개 경로")
    void detailIsPublicWithoutUserId() throws Exception {
        // given
        given(communityPostService.releaseNoteDetail(9L, null)).willReturn(detail());

        // when & then
        mockMvc.perform(get("/core/community/release-notes/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.postId").value("9"))
                .andExpect(jsonPath("$.response.board").value("RELEASE_NOTE"))
                .andExpect(jsonPath("$.response.content").exists());
    }

    @Test
    @DisplayName("상세의 ?lang=은 본문 해석 언어로 서비스에 전달된다 — 폴백은 서비스가 판정")
    void detailBindsLangParam() throws Exception {
        // given
        given(communityPostService.releaseNoteDetail(9L, "zh")).willReturn(detail());

        // when & then
        mockMvc.perform(get("/core/community/release-notes/9").param("lang", "zh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.postId").value("9"))
                .andExpect(jsonPath("$.response.availableLangs[0]").value("ko"));
    }

    @Test
    @DisplayName("RELEASE_NOTE가 아니면(다른 게시판의 post-id) 404 공통 실패 포맷이다 — 존재 은닉")
    void detailHidesNonReleaseNotePost() throws Exception {
        // given
        given(communityPostService.releaseNoteDetail(802L, null))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/core/community/release-notes/802"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("COMMUNITY_POST_NOT_FOUND"))
                .andExpect(jsonPath("$.response").doesNotExist());
    }

    private CommunityPostDetailResponse detail() {
        return new CommunityPostDetailResponse("9", "RELEASE_NOTE", "v1.08 — 커뮤니티 게시판",
                List.of("ko"), "## 주요 기능", new UserRefResponse("1", "관리자"),
                Instant.parse("2026-09-18T00:00:00Z"), Instant.parse("2026-09-18T00:00:00Z"));
    }
}
