package net.java21.crowfoot.api.model.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUser;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.model.dto.CreateModelCommentRequest;
import net.java21.crowfoot.api.model.dto.CreateShareCommentRequest;
import net.java21.crowfoot.api.model.dto.MyShareCommentResponse;
import net.java21.crowfoot.api.model.dto.MyShareReactionResponse;
import net.java21.crowfoot.api.model.dto.ShareCommentResponse;
import net.java21.crowfoot.api.model.dto.ShareFeedbackResponse;
import net.java21.crowfoot.api.model.dto.ShareReactionResponse;
import net.java21.crowfoot.api.model.dto.UpdateShareCommentRequest;
import net.java21.crowfoot.api.model.service.ShareFeedbackService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 문서 피드백 API (08-core/02-model.md Section 1.10.6·1.10.7·1.10.9) — 피드백은 문서(model) 단위다
 * (2026-09-28 재설계). 같은 스레드에 닿는 두 경로 층을 노출한다: ① 공개 토큰 경로 {@code /core/shares/{token}}
 * 아래(댓글은 **선택 인증** — Gateway가 Authorization 토큰이 있으면 검증해 X-USER-ID를 얹고, 없으면 익명으로
 * 통과한다. 반응 토글은 회원전용이라 X-USER-ID가 항상 있다), ② 멤버 문서 경로 {@code /core/workspaces} 아래
 * {@code models}의 feedback·comments·reactions(인증 + 워크스페이스 멤버 — 문서 열기 댓글 탭이 쓴다).
 * 2026-09-27의 오너 관리 경로(shares/{share-id}/comments)는 멤버 경로로 통합·폐지했다.
 */
@RestController
@RequiredArgsConstructor
public class ShareFeedbackController {

    private final ShareFeedbackService feedbackService;

    // ---- 공개 토큰 경로(선택 인증 — 공개 뷰어 댓글 탭) ----

    /** 피드백 초기화(선택 인증) — 반응 상태(회원 신원) + 댓글 목록 1회 */
    @GetMapping("/core/shares/{token}/comments")
    public ApiResponse<ShareFeedbackResponse> listComments(@PathVariable("token") String token) {
        return ApiResponse.success(feedbackService.listFeedback(token, currentUserIdOrNull()));
    }

    /** 댓글 등록(선택 인증, 1.10.7) — 회원은 content만, 비회원은 별명+내용+비밀번호 */
    @PostMapping("/core/shares/{token}/comments")
    public ResponseEntity<ApiResponse<ShareCommentResponse>> createComment(
            @PathVariable("token") String token,
            @Valid @RequestBody CreateShareCommentRequest request) {
        ShareCommentResponse response = feedbackService.createComment(token, currentUserIdOrNull(), request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    /** 댓글 수정(선택 인증, 1.10.7) — 회원은 본인 계정 판정, 비회원은 비밀번호 검증 */
    @PutMapping("/core/shares/{token}/comments/{comment-id}")
    public ApiResponse<ShareCommentResponse> updateComment(
            @PathVariable("token") String token,
            @PathVariable("comment-id") long commentId,
            @Valid @RequestBody UpdateShareCommentRequest request) {
        return ApiResponse.success(feedbackService.updateComment(token, commentId, currentUserIdOrNull(), request));
    }

    /** 반응 토글(회원전용, 1.10.6) — 본문 없는 POST 한 번으로 추가/제거 */
    @PostMapping("/core/shares/{token}/reactions")
    public ApiResponse<ShareReactionResponse> toggleReaction(@PathVariable("token") String token) {
        return ApiResponse.success(
                feedbackService.toggleReaction(token, CurrentUserHolder.get().userId()));
    }

    /** 댓글 삭제(선택 인증, 1.10.7) — 회원은 본인 계정 판정(본문 없음), 비회원은 비밀번호 검증 */
    @DeleteMapping("/core/shares/{token}/comments/{comment-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(
            @PathVariable("token") String token,
            @PathVariable("comment-id") long commentId,
            @RequestBody(required = false) DeleteShareCommentRequest request) {
        feedbackService.deleteComment(token, commentId, currentUserIdOrNull(),
                request == null ? null : request.password());
    }

    // ---- 멤버 문서 경로(인증 — 문서 열기 댓글 탭) ----

    /** 피드백 초기화(멤버면 역할 무관) — 반응 상태 + 댓글 목록 1회. 토큰 경로와 같은 문서 스레드 */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/feedback")
    public ApiResponse<ShareFeedbackResponse> listModelFeedback(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId) {
        return ApiResponse.success(feedbackService.listModelFeedback(
                CurrentUserHolder.get().userId(), workspaceId, modelId));
    }

    /** 멤버 댓글 등록(Commenter 이상, 1.10.7) — {content} / 오너 답글 {content, parentCommentId} */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/comments")
    public ResponseEntity<ApiResponse<ShareCommentResponse>> createModelComment(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @Valid @RequestBody CreateModelCommentRequest request) {
        ShareCommentResponse response = feedbackService.createModelComment(
                CurrentUserHolder.get().userId(), workspaceId, modelId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId
                        + "/models/" + modelId + "/comments/" + response.commentId()))
                .body(ApiResponse.success(response));
    }

    /** 멤버 댓글 수정(Commenter 이상, 1.10.7) — 본인 댓글만 {content} */
    @PutMapping("/core/workspaces/{workspace-id}/models/{model-id}/comments/{comment-id}")
    public ApiResponse<ShareCommentResponse> updateModelComment(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("comment-id") long commentId,
            @Valid @RequestBody UpdateShareCommentRequest request) {
        return ApiResponse.success(feedbackService.updateModelComment(
                CurrentUserHolder.get().userId(), workspaceId, modelId, commentId, request));
    }

    /** 멤버 댓글 삭제(Commenter 이상, 1.10.7) — 본인·오너(문서 작성자)·관리자 */
    @DeleteMapping("/core/workspaces/{workspace-id}/models/{model-id}/comments/{comment-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteModelComment(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("comment-id") long commentId) {
        feedbackService.deleteModelComment(CurrentUserHolder.get().userId(), workspaceId, modelId, commentId);
    }

    /** 반응 토글(멤버면 역할 무관, 1.10.6) — 본문 없는 POST 한 번으로 추가/제거 */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/reactions")
    public ApiResponse<ShareReactionResponse> toggleModelReaction(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId) {
        return ApiResponse.success(feedbackService.toggleModelReaction(
                CurrentUserHolder.get().userId(), workspaceId, modelId));
    }

    // ---- 내 피드백 역조회(1.10.9) ----

    /** 내가 작성한 문서 댓글(인증, 1.10.9) — 커뮤니티 "내 댓글" 메뉴. 최신 활동순 */
    @GetMapping("/core/accounts/me/share-comments")
    public ListApiResponse<MyShareCommentResponse> myComments() {
        return ListApiResponse.of(feedbackService.myComments(CurrentUserHolder.get().userId()));
    }

    /** 내가 좋아요한 문서(인증, 1.10.9) — 커뮤니티 "좋아요 문서" 메뉴. 최근 반응순 */
    @GetMapping("/core/accounts/me/share-reactions")
    public ListApiResponse<MyShareReactionResponse> myReactions() {
        return ListApiResponse.of(feedbackService.myReactions(CurrentUserHolder.get().userId()));
    }

    /** 선택 인증 경로의 회원 신원 — 헤더가 없으면(비회원 요청) null */
    private static Long currentUserIdOrNull() {
        CurrentUser user = CurrentUserHolder.getOrNull();
        return user == null ? null : user.userId();
    }

    /** 댓글 삭제 요청 몸통 — 비회원의 비밀번호만 담는다(회원 삭제는 본문 없이) */
    record DeleteShareCommentRequest(String password) {
    }
}
