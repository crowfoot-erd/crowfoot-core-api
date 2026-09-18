package net.java21.crowfoot.api.community.controller;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.community.dto.CommunityCommentResponse;
import net.java21.crowfoot.api.community.service.CommunityCommentService;
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

/** 커뮤니티 코멘트 API 웹 계층 테스트 (08-core/08-community.md) — 경로·201 Location·릴리스 노트 차단 전파 */
@WebMvcTest(CommunityCommentController.class)
class CommunityCommentControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommunityCommentService communityCommentService;

    @Test
    @DisplayName("생성은 201 + Location(코멘트 외부 URI)을 응답한다")
    void createReturns201WithExternalLocation() throws Exception {
        // given
        given(communityCommentService.create(7L, 41L,
                new net.java21.crowfoot.api.community.dto.CreateCommunityCommentRequest("좋은 제안입니다")))
                .willReturn(comment());

        // when & then
        mockMvc.perform(post("/core/community/posts/41/comments").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"좋은 제안입니다\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/community/comments/61"))
                .andExpect(jsonPath("$.response.commentId").value("61"))
                .andExpect(jsonPath("$.response.author.name").value("marco"));
    }

    @Test
    @DisplayName("빈 코멘트는 400 검증 실패이다")
    void createRejectsBlankContent() throws Exception {
        mockMvc.perform(post("/core/community/posts/41/comments").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("릴리스 노트 게시글의 코멘트 생성은 400 COMMUNITY_COMMENT_NOT_ALLOWED를 전파한다")
    void createPropagatesNotAllowed() throws Exception {
        // given
        given(communityCommentService.create(7L, 51L,
                new net.java21.crowfoot.api.community.dto.CreateCommunityCommentRequest("댓글")))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_ALLOWED));

        // when & then
        mockMvc.perform(post("/core/community/posts/51/comments").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"댓글\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("COMMUNITY_COMMENT_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("목록은 페이징 없는 목록 포맷으로 오래된 순 응답을 전달한다")
    void listReturnsComments() throws Exception {
        // given
        given(communityCommentService.list(41L)).willReturn(ListApiResponse.of(List.of(comment())));

        // when & then
        mockMvc.perform(get("/core/community/posts/41/comments").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].commentId").value("61"));
    }

    @Test
    @DisplayName("없는 코멘트 수정은 404 COMMUNITY_COMMENT_NOT_FOUND이다")
    void patchPropagatesNotFound() throws Exception {
        // given
        given(communityCommentService.patch(7L, 99L,
                new net.java21.crowfoot.api.community.dto.UpdateCommunityCommentRequest("바뀐 댓글")))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));

        // when & then
        mockMvc.perform(patch("/core/community/comments/99").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"바뀐 댓글\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("COMMUNITY_COMMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("삭제는 204 본문 없음이다")
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/core/community/comments/61").header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
    }

    private CommunityCommentResponse comment() {
        return new CommunityCommentResponse("61", "41", "좋은 제안입니다", new UserRefResponse("7", "marco"),
                Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"));
    }
}
