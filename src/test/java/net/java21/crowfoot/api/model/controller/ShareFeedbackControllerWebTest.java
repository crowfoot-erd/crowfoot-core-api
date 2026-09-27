package net.java21.crowfoot.api.model.controller;

import net.java21.crowfoot.api.model.dto.CreateOwnerShareCommentRequest;
import net.java21.crowfoot.api.model.dto.CreateShareCommentRequest;
import net.java21.crowfoot.api.model.dto.ShareCommentResponse;
import net.java21.crowfoot.api.model.dto.ShareFeedbackResponse;
import net.java21.crowfoot.api.model.dto.ShareReactionResponse;
import net.java21.crowfoot.api.model.service.ShareFeedbackService;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공유 문서 피드백 API 웹 계층 테스트 (08-core/02-model.md Section 1.10.6·1.10.7) —
 * 무인증 4경로(X-USER-ID 없음)·방문자 쿠키 발급(Set-Cookie 규격)·오너 관리 경로(인증)·공통 404/410.
 */
@WebMvcTest(ShareFeedbackController.class)
class ShareFeedbackControllerWebTest {

    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShareFeedbackService feedbackService;

    @Test
    @DisplayName("피드백 초기화는 X-USER-ID 없이도 200 — 반응 상태와 댓글 목록을 1회 fetch로, 쿠키는 발급하지 않는다")
    void listCommentsIsPublicAndIssuesNoCookie() throws Exception {
        given(feedbackService.listFeedback("tok123", ACTOR)).willReturn(new ShareFeedbackResponse(
                7L, true, List.of(
                new ShareCommentResponse("31", null, "방문자", "구조 좋네요", false,
                        Instant.parse("2026-09-20T10:00:00Z")),
                new ShareCommentResponse("32", "31", "작성자", "감사합니다", true,
                        Instant.parse("2026-09-20T10:05:00Z")))));

        mockMvc.perform(get("/core/shares/tok123/comments").cookie(actorCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.reactionCount").value(7))
                .andExpect(jsonPath("$.response.reacted").value(true))
                .andExpect(jsonPath("$.response.comments[0].commentId").value("31"))
                .andExpect(jsonPath("$.response.comments[0].parentCommentId").doesNotExist())
                .andExpect(jsonPath("$.response.comments[0].owner").value(false))
                .andExpect(jsonPath("$.response.comments[1].parentCommentId").value("31"))
                .andExpect(jsonPath("$.response.comments[1].owner").value(true))
                // 읽기만 한 방문자에게 상태(쿠키)를 만들지 않는다
                .andExpect(header().string("Set-Cookie", not(containsString("crowfoot_share_actor"))));
    }

    @Test
    @DisplayName("익명 댓글 등록은 X-USER-ID 없이도 201이고, 첫 피드백에 방문자 쿠키를 발급해 되돌린다")
    void createCommentIssuesActorCookieOnFirstFeedback() throws Exception {
        given(feedbackService.createComment(eq("tok123"), anyString(),
                eq(new CreateShareCommentRequest("방문자", "구조 좋네요"))))
                .willReturn(new ShareCommentResponse("31", null, "방문자", "구조 좋네요", false,
                        Instant.parse("2026-09-20T10:00:00Z")));

        // 쿠키 없음 = 첫 방문자 — 서버가 만든 UUID로 발급(예측 불가라 존재·규격만 주장)
        mockMvc.perform(post("/core/shares/tok123/comments")
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"방문자\",\"content\":\"구조 좋네요\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.commentId").value("31"))
                .andExpect(jsonPath("$.response.owner").value(false))
                .andExpect(header().string("Set-Cookie", containsString("crowfoot_share_actor=")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/core/shares")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=34560000")));
    }

    @Test
    @DisplayName("쿠키가 이미 있으면 그 값을 그대로 서비스에 넘기고 재발급하지 않는다")
    void createCommentReusesExistingActorCookie() throws Exception {
        given(feedbackService.createComment(eq("tok123"), eq(ACTOR),
                eq(new CreateShareCommentRequest("방문자", "두 번째 댓글"))))
                .willReturn(new ShareCommentResponse("33", null, "방문자", "두 번째 댓글", false,
                        Instant.parse("2026-09-20T11:00:00Z")));

        mockMvc.perform(post("/core/shares/tok123/comments").cookie(actorCookie())
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"방문자\",\"content\":\"두 번째 댓글\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Set-Cookie", not(containsString("crowfoot_share_actor"))));
        then(feedbackService).should().createComment(eq("tok123"), eq(ACTOR),
                eq(new CreateShareCommentRequest("방문자", "두 번째 댓글")));
    }

    @Test
    @DisplayName("조작된 쿠키 값은 새 UUID로 시작한다 — UUID 규격이 아니면 발급한다")
    void createCommentReissuesGarbageCookie() throws Exception {
        given(feedbackService.createComment(eq("tok123"), anyString(),
                eq(new CreateShareCommentRequest("방문자", "댓글"))))
                .willReturn(new ShareCommentResponse("31", null, "방문자", "댓글", false,
                        Instant.parse("2026-09-20T10:00:00Z")));

        mockMvc.perform(post("/core/shares/tok123/comments")
                        .cookie(new jakarta.servlet.http.Cookie("crowfoot_share_actor", "garbage"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"방문자\",\"content\":\"댓글\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Set-Cookie", containsString("crowfoot_share_actor=")));
    }

    @Test
    @DisplayName("반응 토글은 본문 없는 무인증 POST로 200 — 방문자 쿠키를 발급하고 정착값을 내려준다")
    void toggleReactionReturnsSettledState() throws Exception {
        given(feedbackService.toggleReaction(eq("tok123"), anyString()))
                .willReturn(new ShareReactionResponse(8L, true));

        mockMvc.perform(post("/core/shares/tok123/reactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.reactionCount").value(8))
                .andExpect(jsonPath("$.response.reacted").value(true))
                .andExpect(header().string("Set-Cookie", containsString("crowfoot_share_actor=")));
    }

    @Test
    @DisplayName("반응 토글 재요청(쿠키 있음)은 재발급 없이 그 방문자로 판정한다 — 토글 OFF")
    void toggleReactionReusesExistingActorCookie() throws Exception {
        given(feedbackService.toggleReaction("tok123", ACTOR))
                .willReturn(new ShareReactionResponse(7L, false));

        mockMvc.perform(post("/core/shares/tok123/reactions").cookie(actorCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.reacted").value(false))
                .andExpect(header().string("Set-Cookie", not(containsString("crowfoot_share_actor"))));
    }

    @Test
    @DisplayName("익명 본인 댓글 삭제는 쿠키를 싣고 204 본문 없음이다")
    void deleteCommentReturns204() throws Exception {
        mockMvc.perform(delete("/core/shares/tok123/comments/31").cookie(actorCookie()))
                .andExpect(status().isNoContent());
        then(feedbackService).should().deleteComment("tok123", 31L, ACTOR);
    }

    @Test
    @DisplayName("댓글 삭제는 쿠키 불일치·오너 댓글이면 403 PERMISSION_DENIED다 — 서버가 최종 판정")
    void deleteCommentReturns403ForOthers() throws Exception {
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .given(feedbackService).deleteComment("tok123", 31L, ACTOR);

        mockMvc.perform(delete("/core/shares/tok123/comments/31").cookie(actorCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("댓글 삭제는 이미 없으면 404 SHARE_COMMENT_NOT_FOUND다")
    void deleteCommentReturns404() throws Exception {
        willThrow(new BusinessException(ErrorCode.SHARE_COMMENT_NOT_FOUND))
                .given(feedbackService).deleteComment("tok123", 31L, ACTOR);

        mockMvc.perform(delete("/core/shares/tok123/comments/31").cookie(actorCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SHARE_COMMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("피드백 경로도 공개 조회와 같은 판정 — 알 수 없는 토큰 404, 기간 밖 410")
    void feedbackSharesTokenVerdicts() throws Exception {
        willThrow(new BusinessException(ErrorCode.SHARE_NOT_FOUND))
                .given(feedbackService).listFeedback(eq("nope"), any());
        willThrow(new BusinessException(ErrorCode.SHARE_INACTIVE))
                .given(feedbackService).listFeedback(eq("expired"), any());

        mockMvc.perform(get("/core/shares/nope/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SHARE_NOT_FOUND"));
        mockMvc.perform(get("/core/shares/expired/comments"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.header.resultCode").value("SHARE_INACTIVE"));
    }

    @Test
    @DisplayName("오너 답글은 인증 경로로 201 — parentCommentId 필수, 작성자 배지 원료(owner·작성자명)")
    void createOwnerCommentReturns201() throws Exception {
        given(feedbackService.createOwnerComment(7L, 77L, 501L, 9L,
                new CreateOwnerShareCommentRequest(31L, "감사합니다")))
                .willReturn(new ShareCommentResponse("32", "31", "오너", "감사합니다", true,
                        Instant.parse("2026-09-20T10:05:00Z")));

        mockMvc.perform(post("/core/workspaces/77/models/501/shares/9/comments")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"parentCommentId\":31,\"content\":\"감사합니다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.commentId").value("32"))
                .andExpect(jsonPath("$.response.parentCommentId").value("31"))
                .andExpect(jsonPath("$.response.nickname").value("오너"))
                .andExpect(jsonPath("$.response.owner").value(true));
    }

    @Test
    @DisplayName("오너 답글은 부모가 답글이면 400 INVALID_REQUEST다 — 1단계 제한")
    void createOwnerCommentRejectsNestedParent() throws Exception {
        willThrow(BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.share.comment.parent"))
                .given(feedbackService)
                .createOwnerComment(eq(7L), eq(77L), eq(501L), eq(9L), any());

        mockMvc.perform(post("/core/workspaces/77/models/501/shares/9/comments")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"parentCommentId\":32,\"content\":\"2단계 답글\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("오너 댓글 관리 삭제는 인증 경로로 204 본문 없음이다")
    void deleteOwnerCommentReturns204() throws Exception {
        mockMvc.perform(delete("/core/workspaces/77/models/501/shares/9/comments/31")
                        .header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
        then(feedbackService).should().deleteOwnerComment(7L, 77L, 501L, 9L, 31L);
    }

    private static jakarta.servlet.http.Cookie actorCookie() {
        return new jakarta.servlet.http.Cookie("crowfoot_share_actor", ACTOR);
    }
}
