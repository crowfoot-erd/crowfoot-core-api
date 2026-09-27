package net.java21.crowfoot.api.model.controller;

import net.java21.crowfoot.api.model.dto.CreateModelCommentRequest;
import net.java21.crowfoot.api.model.dto.CreateShareCommentRequest;
import net.java21.crowfoot.api.model.dto.MyShareCommentResponse;
import net.java21.crowfoot.api.model.dto.MyShareReactionResponse;
import net.java21.crowfoot.api.model.dto.ShareCommentResponse;
import net.java21.crowfoot.api.model.dto.ShareFeedbackResponse;
import net.java21.crowfoot.api.model.dto.ShareReactionResponse;
import net.java21.crowfoot.api.model.dto.UpdateShareCommentRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문서 피드백 API 웹 계층 테스트 (08-core/02-model.md Section 1.10.6·1.10.7·1.10.9) —
 * 스레드는 문서 단위다(2026-09-28). 공개 토큰 경로(댓글 선택 인증·반응 회원전용)와
 * 멤버 문서 경로(인증 — 문서 열기 댓글 탭: feedback·comments·reactions)를 함께 검증하고,
 * 내 피드백 역조회 accounts/me 2경로(인증), 공통 404/410·403을 담는다.
 */
@WebMvcTest(ShareFeedbackController.class)
class ShareFeedbackControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShareFeedbackService feedbackService;

    // --- 공개 토큰 경로 (선택 인증 — 공개 뷰어 댓글 탭)

    @Test
    @DisplayName("피드백 초기화는 X-USER-ID 없이도 200(비회원 reacted=false) — 댓글은 authorType으로 구분해 내려준다")
    void listCommentsIsPublicForGuests() throws Exception {
        given(feedbackService.listFeedback("tok123", null)).willReturn(new ShareFeedbackResponse(
                7L, false, List.of(
                new ShareCommentResponse("31", null, "방문자", "guest", "구조 좋네요", false,
                        Instant.parse("2026-09-20T10:00:00Z")),
                new ShareCommentResponse("33", null, "다른회원", "member", "회원도 답니다", false,
                        Instant.parse("2026-09-20T10:02:00Z")),
                new ShareCommentResponse("32", "31", "오너", "owner", "감사합니다", false,
                        Instant.parse("2026-09-20T10:05:00Z")))));

        mockMvc.perform(get("/core/shares/tok123/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.reactionCount").value(7))
                .andExpect(jsonPath("$.response.reacted").value(false))
                .andExpect(jsonPath("$.response.comments[0].commentId").value("31"))
                .andExpect(jsonPath("$.response.comments[0].parentCommentId").doesNotExist())
                .andExpect(jsonPath("$.response.comments[0].authorType").value("guest"))
                .andExpect(jsonPath("$.response.comments[1].authorType").value("member"))
                .andExpect(jsonPath("$.response.comments[2].parentCommentId").value("31"))
                .andExpect(jsonPath("$.response.comments[2].authorType").value("owner"))
                .andExpect(jsonPath("$.response.comments[0].edited").value(false));
    }

    @Test
    @DisplayName("피드백 초기화는 X-USER-ID가 있으면 회원 신원으로 조회한다 — reacted가 그 회원 기준이 된다")
    void listCommentsCarriesMemberIdentity() throws Exception {
        given(feedbackService.listFeedback("tok123", 7L)).willReturn(new ShareFeedbackResponse(
                7L, true, List.of()));

        mockMvc.perform(get("/core/shares/tok123/comments").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.reacted").value(true));
        then(feedbackService).should().listFeedback("tok123", 7L);
    }

    @Test
    @DisplayName("비회원 댓글 등록은 X-USER-ID 없이도 201 — 별명+내용+비밀번호를 받는다")
    void createCommentForGuest() throws Exception {
        given(feedbackService.createComment(eq("tok123"), eq(null),
                eq(new CreateShareCommentRequest("방문자", "구조 좋네요", "pass1234"))))
                .willReturn(new ShareCommentResponse("31", null, "방문자", "guest", "구조 좋네요", false,
                        Instant.parse("2026-09-20T10:00:00Z")));

        mockMvc.perform(post("/core/shares/tok123/comments")
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"방문자\",\"content\":\"구조 좋네요\",\"password\":\"pass1234\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.commentId").value("31"))
                .andExpect(jsonPath("$.response.authorType").value("guest"))
                .andExpect(jsonPath("$.response.nickname").value("방문자"));
    }

    @Test
    @DisplayName("회원 댓글 등록은 X-USER-ID를 실어 계정 판정으로 — 몸통은 내용만 보내도 된다")
    void createCommentForMember() throws Exception {
        given(feedbackService.createComment(eq("tok123"), eq(8L),
                eq(new CreateShareCommentRequest(null, "회원 댓글", null))))
                .willReturn(new ShareCommentResponse("33", null, "다른회원", "member", "회원 댓글", false,
                        Instant.parse("2026-09-20T11:00:00Z")));

        mockMvc.perform(post("/core/shares/tok123/comments")
                        .header("X-USER-ID", "8")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"회원 댓글\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.authorType").value("member"))
                .andExpect(jsonPath("$.response.nickname").value("다른회원"));
        then(feedbackService).should().createComment(eq("tok123"), eq(8L),
                eq(new CreateShareCommentRequest(null, "회원 댓글", null)));
    }

    @Test
    @DisplayName("비회원 댓글 등록은 비밀번호가 4자 미만이면 400 INVALID_REQUEST다 — 빈칸 검증")
    void createCommentRejectsShortPassword() throws Exception {
        mockMvc.perform(post("/core/shares/tok123/comments")
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"방문자\",\"content\":\"구조 좋네요\",\"password\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("반응 토글은 회원전용 — X-USER-ID가 있으면 200 정착값, 없으면 401(XUserIdFilter)")
    void toggleReactionRequiresMember() throws Exception {
        given(feedbackService.toggleReaction("tok123", 7L))
                .willReturn(new ShareReactionResponse(8L, true));

        mockMvc.perform(post("/core/shares/tok123/reactions").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.reactionCount").value(8))
                .andExpect(jsonPath("$.response.reacted").value(true));

        mockMvc.perform(post("/core/shares/tok123/reactions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_TOKEN_INVALID"));
        then(feedbackService).should().toggleReaction("tok123", 7L);
    }

    @Test
    @DisplayName("댓글 수정(PUT)은 회원 신원 또는 비밀번호로 — 응답은 고친 내용과 edited=true")
    void updateCommentReturnsEdited() throws Exception {
        given(feedbackService.updateComment(eq("tok123"), eq(31L), eq(null),
                eq(new UpdateShareCommentRequest("고친 내용", "pass1234"))))
                .willReturn(new ShareCommentResponse("31", null, "방문자", "guest", "고친 내용", true,
                        Instant.parse("2026-09-20T10:00:00Z")));

        mockMvc.perform(put("/core/shares/tok123/comments/31")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"고친 내용\",\"password\":\"pass1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.content").value("고친 내용"))
                .andExpect(jsonPath("$.response.edited").value(true));

        given(feedbackService.updateComment(eq("tok123"), eq(31L), eq(8L),
                eq(new UpdateShareCommentRequest("회원 수정", null))))
                .willReturn(new ShareCommentResponse("31", null, "다른회원", "member", "회원 수정", true,
                        Instant.parse("2026-09-20T10:00:00Z")));
        mockMvc.perform(put("/core/shares/tok123/comments/31")
                        .header("X-USER-ID", "8")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"회원 수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.authorType").value("member"));
    }

    @Test
    @DisplayName("댓글 수정은 판정 실패(비밀번호 불일치·타인)면 403 PERMISSION_DENIED다")
    void updateCommentReturns403() throws Exception {
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .given(feedbackService)
                .updateComment(eq("tok123"), eq(31L), eq(null), any());

        mockMvc.perform(put("/core/shares/tok123/comments/31")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"타인 수정\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("댓글 삭제는 비회원은 비밀번호 몸통으로, 회원은 X-USER-ID로 — 204 본문 없음")
    void deleteCommentReturns204() throws Exception {
        mockMvc.perform(delete("/core/shares/tok123/comments/31")
                        .contentType(APPLICATION_JSON)
                        .content("{\"password\":\"pass1234\"}"))
                .andExpect(status().isNoContent());
        then(feedbackService).should().deleteComment("tok123", 31L, null, "pass1234");

        mockMvc.perform(delete("/core/shares/tok123/comments/33").header("X-USER-ID", "8"))
                .andExpect(status().isNoContent());
        then(feedbackService).should().deleteComment("tok123", 33L, 8L, null);
    }

    @Test
    @DisplayName("댓글 삭제는 판정 실패면 403, 없는 댓글이면 404 MODEL_COMMENT_NOT_FOUND다")
    void deleteCommentReturns403And404() throws Exception {
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .given(feedbackService).deleteComment("tok123", 31L, null, "wrong-pass");
        willThrow(new BusinessException(ErrorCode.MODEL_COMMENT_NOT_FOUND))
                .given(feedbackService).deleteComment("tok123", 99L, null, "pass1234");

        mockMvc.perform(delete("/core/shares/tok123/comments/31")
                        .contentType(APPLICATION_JSON)
                        .content("{\"password\":\"wrong-pass\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        mockMvc.perform(delete("/core/shares/tok123/comments/99")
                        .contentType(APPLICATION_JSON)
                        .content("{\"password\":\"pass1234\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MODEL_COMMENT_NOT_FOUND"));
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

    // --- 멤버 문서 경로 (인증 — 문서 열기 댓글 탭)

    @Test
    @DisplayName("멤버 피드백 초기화는 X-USER-ID로 — 토큰 경로와 같은 형태의 문서 스레드")
    void listModelFeedbackForMember() throws Exception {
        given(feedbackService.listModelFeedback(7L, 77L, 501L)).willReturn(new ShareFeedbackResponse(
                7L, true, List.of(new ShareCommentResponse("31", null, "방문자", "guest", "구조 좋네요",
                false, Instant.parse("2026-09-20T10:00:00Z")))));

        mockMvc.perform(get("/core/workspaces/77/models/501/feedback").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.reactionCount").value(7))
                .andExpect(jsonPath("$.response.reacted").value(true))
                .andExpect(jsonPath("$.response.comments[0].commentId").value("31"));

        mockMvc.perform(get("/core/workspaces/77/models/501/feedback"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_TOKEN_INVALID"));
        then(feedbackService).should().listModelFeedback(7L, 77L, 501L);
    }

    @Test
    @DisplayName("멤버 댓글 등록은 인증 경로로 201 — 몸통은 내용만(원댓글) 또는 내용+parentCommentId(오너 답글)")
    void createModelCommentReturns201() throws Exception {
        given(feedbackService.createModelComment(7L, 77L, 501L,
                new CreateModelCommentRequest("감사합니다", 31L)))
                .willReturn(new ShareCommentResponse("32", "31", "오너", "owner", "감사합니다", false,
                        Instant.parse("2026-09-20T10:05:00Z")));

        mockMvc.perform(post("/core/workspaces/77/models/501/comments")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"감사합니다\",\"parentCommentId\":31}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/v1/core/workspaces/77/models/501/comments/32"))
                .andExpect(jsonPath("$.response.commentId").value("32"))
                .andExpect(jsonPath("$.response.parentCommentId").value("31"))
                .andExpect(jsonPath("$.response.nickname").value("오너"))
                .andExpect(jsonPath("$.response.authorType").value("owner"));
    }

    @Test
    @DisplayName("멤버 댓글 등록은 내용이 비면 400 INVALID_REQUEST다 — 빈칸 검증")
    void createModelCommentRejectsBlankContent() throws Exception {
        mockMvc.perform(post("/core/workspaces/77/models/501/comments")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("오너 답글은 부모가 답글이면 400 INVALID_REQUEST다 — 1단계 제한")
    void createModelCommentRejectsNestedParent() throws Exception {
        willThrow(BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.share.comment.parent"))
                .given(feedbackService)
                .createModelComment(eq(7L), eq(77L), eq(501L), any());

        mockMvc.perform(post("/core/workspaces/77/models/501/comments")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"2단계 답글\",\"parentCommentId\":32}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("멤버 댓글 수정은 인증 경로로 200 — 본인 판정은 서비스가, 몸통은 내용만")
    void updateModelCommentReturnsEdited() throws Exception {
        given(feedbackService.updateModelComment(8L, 77L, 501L, 31L,
                new UpdateShareCommentRequest("고친 내용", null)))
                .willReturn(new ShareCommentResponse("31", null, "다른회원", "member", "고친 내용", true,
                        Instant.parse("2026-09-20T10:00:00Z")));

        mockMvc.perform(put("/core/workspaces/77/models/501/comments/31")
                        .header("X-USER-ID", "8")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"고친 내용\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.content").value("고친 내용"))
                .andExpect(jsonPath("$.response.edited").value(true));
    }

    @Test
    @DisplayName("멤버 댓글 삭제·반응 토글은 인증 경로 — 204 본문 없음·200 정착값")
    void deleteModelCommentAndToggleReaction() throws Exception {
        mockMvc.perform(delete("/core/workspaces/77/models/501/comments/31")
                        .header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
        then(feedbackService).should().deleteModelComment(7L, 77L, 501L, 31L);

        given(feedbackService.toggleModelReaction(7L, 77L, 501L))
                .willReturn(new ShareReactionResponse(8L, true));
        mockMvc.perform(post("/core/workspaces/77/models/501/reactions")
                        .header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.reactionCount").value(8))
                .andExpect(jsonPath("$.response.reacted").value(true));
        then(feedbackService).should().toggleModelReaction(7L, 77L, 501L);
    }

    // --- 내 피드백 역조회 (1.10.9 — accounts/me)

    @Test
    @DisplayName("내 댓글 목록(1.10.9)은 인증 경로 — 댓글에 문서 메타(shareToken·modelName)를 얹어 내려준다")
    void myCommentsCarriesModelMeta() throws Exception {
        given(feedbackService.myComments(7L)).willReturn(List.of(
                new MyShareCommentResponse("33", null, "답글 단 내용", false,
                        Instant.parse("2026-09-27T09:00:00Z"), "tok123", "주문 관리", "PostgreSQL"),
                new MyShareCommentResponse("31", null, "첫 댓글", true,
                        Instant.parse("2026-09-26T09:00:00Z"), "tok456", "게시판", "MySQL")));

        mockMvc.perform(get("/core/accounts/me/share-comments").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].commentId").value("33"))
                .andExpect(jsonPath("$.responses[0].parentCommentId").doesNotExist())
                .andExpect(jsonPath("$.responses[0].edited").value(false))
                .andExpect(jsonPath("$.responses[0].shareToken").value("tok123"))
                .andExpect(jsonPath("$.responses[0].modelName").value("주문 관리"))
                .andExpect(jsonPath("$.responses[0].databaseType").value("PostgreSQL"))
                .andExpect(jsonPath("$.responses[1].edited").value(true));

        mockMvc.perform(get("/core/accounts/me/share-comments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_TOKEN_INVALID"));
        then(feedbackService).should().myComments(7L);
    }

    @Test
    @DisplayName("내 좋아요 목록(1.10.9)은 인증 경로 — 문서 메타와 카운터 3종을 얹어 내려준다")
    void myReactionsCarriesCounters() throws Exception {
        given(feedbackService.myReactions(7L)).willReturn(List.of(new MyShareReactionResponse(
                Instant.parse("2026-09-27T08:00:00Z"), "tok123", "주문 관리", "쇼케이스 문서",
                "PostgreSQL", 42L, 7L, 3L)));

        mockMvc.perform(get("/core/accounts/me/share-reactions").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].reactedAt").value("2026-09-27T08:00:00Z"))
                .andExpect(jsonPath("$.responses[0].shareToken").value("tok123"))
                .andExpect(jsonPath("$.responses[0].modelName").value("주문 관리"))
                .andExpect(jsonPath("$.responses[0].description").value("쇼케이스 문서"))
                .andExpect(jsonPath("$.responses[0].databaseType").value("PostgreSQL"))
                .andExpect(jsonPath("$.responses[0].viewCount").value(42))
                .andExpect(jsonPath("$.responses[0].reactionCount").value(7))
                .andExpect(jsonPath("$.responses[0].commentCount").value(3));
        then(feedbackService).should().myReactions(7L);
    }
}
