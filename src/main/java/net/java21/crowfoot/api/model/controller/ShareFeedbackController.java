package net.java21.crowfoot.api.model.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.model.dto.CreateOwnerShareCommentRequest;
import net.java21.crowfoot.api.model.dto.CreateShareCommentRequest;
import net.java21.crowfoot.api.model.dto.ShareCommentResponse;
import net.java21.crowfoot.api.model.dto.ShareFeedbackResponse;
import net.java21.crowfoot.api.model.dto.ShareReactionResponse;
import net.java21.crowfoot.api.model.service.ShareFeedbackService;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * 공유 문서 피드백 API (08-core/02-model.md Section 1.10.6·1.10.7) — 공개 경로
 * {@code /core/shares/{token}} 아래(무인증 — Gateway 화이트리스트)와 오너 관리 경로
 * {@code /core/workspaces} 아래 shares의 comments(인증). 방문자 식별은 서버 발급
 * crowfoot_share_actor 쿠키(UUID)로 — 읽기만 할 때는 발급하지 않고 첫 반응·댓글에서
 * 발급해 되돌린다(1.10.6 쿠키 규격과 같은 Path·보호 속성).
 */
@RestController
@RequiredArgsConstructor
public class ShareFeedbackController {

    /** 방문자 식별 쿠키 — 반응 중복 판정·익명 댓글 본인 삭제의 근거(1.10.6), 값은 UUID */
    static final String ACTOR_COOKIE = "crowfoot_share_actor";
    private static final String ACTOR_COOKIE_PATH = "/api/v1/core/shares";
    private static final Duration ACTOR_MAX_AGE = Duration.ofDays(400);

    private final ShareFeedbackService feedbackService;

    /** 피드백 초기화(무인증) — 반응 상태 + 댓글 목록 1회. 읽기 경로라 쿠키는 발급하지 않는다 */
    @GetMapping("/core/shares/{token}/comments")
    public ApiResponse<ShareFeedbackResponse> listComments(
            @PathVariable("token") String token,
            @CookieValue(name = ACTOR_COOKIE, required = false) String actor) {
        return ApiResponse.success(feedbackService.listFeedback(token, actor));
    }

    /** 익명 댓글 등록(무인증, 1.10.7) — 첫 피드백에서 방문자 쿠키를 발급해 되돌린다 */
    @PostMapping("/core/shares/{token}/comments")
    public ResponseEntity<ApiResponse<ShareCommentResponse>> createComment(
            @PathVariable("token") String token,
            @CookieValue(name = ACTOR_COOKIE, required = false) String actor,
            @Valid @RequestBody CreateShareCommentRequest request) {
        ActorKey actorKey = issueActorKey(actor);
        ShareCommentResponse response = feedbackService.createComment(token, actorKey.value(), request);
        return withActorCookie(ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response)), actorKey);
    }

    /** 반응 토글(무인증, 1.10.6) — 본문 없는 POST 한 번으로 추가/제거. 첫 반응에서 방문자 쿠키 발급 */
    @PostMapping("/core/shares/{token}/reactions")
    public ResponseEntity<ApiResponse<ShareReactionResponse>> toggleReaction(
            @PathVariable("token") String token,
            @CookieValue(name = ACTOR_COOKIE, required = false) String actor) {
        ActorKey actorKey = issueActorKey(actor);
        ShareReactionResponse response = feedbackService.toggleReaction(token, actorKey.value());
        return withActorCookie(ResponseEntity.ok(ApiResponse.success(response)), actorKey);
    }

    /** 익명 본인 댓글 삭제(무인증, 1.10.7) — 쿠키 visitor_key 일치만. 오너 댓글·타인 댓글 403 */
    @DeleteMapping("/core/shares/{token}/comments/{comment-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(
            @PathVariable("token") String token,
            @PathVariable("comment-id") long commentId,
            @CookieValue(name = ACTOR_COOKIE, required = false) String actor) {
        feedbackService.deleteComment(token, commentId, actor);
    }

    /** 오너 답글 등록(인증, 1.10.7) — 문서 작성자·관리자만, parentCommentId 필수(1단계 제한) */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/shares/{share-id}/comments")
    public ResponseEntity<ApiResponse<ShareCommentResponse>> createOwnerComment(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("share-id") long shareId,
            @Valid @RequestBody CreateOwnerShareCommentRequest request) {
        ShareCommentResponse response = feedbackService.createOwnerComment(
                CurrentUserHolder.get().userId(), workspaceId, modelId, shareId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId
                        + "/models/" + modelId + "/shares/" + shareId
                        + "/comments/" + response.commentId()))
                .body(ApiResponse.success(response));
    }

    /** 댓글 관리 삭제(인증, 1.10.7) — 오너·관리자는 그 링크의 모든 댓글·답글 삭제(스팸 대응) */
    @DeleteMapping("/core/workspaces/{workspace-id}/models/{model-id}/shares/{share-id}/comments/{comment-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOwnerComment(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("share-id") long shareId,
            @PathVariable("comment-id") long commentId) {
        feedbackService.deleteOwnerComment(
                CurrentUserHolder.get().userId(), workspaceId, modelId, shareId, commentId);
    }

    /** 발급한 방문자 키 — 새로 만들었으면(issued) 응답에 Set-Cookie로 되돌린다 */
    private record ActorKey(String value, boolean issued) {
    }

    /** 쿠키 값이 UUID 규격이 아니면(없음·조작) 새로 발급 — 1.10.6과 같은 Path·보호 속성 */
    private static ActorKey issueActorKey(String cookieValue) {
        if (cookieValue != null && !cookieValue.isBlank()) {
            try {
                return new ActorKey(UUID.fromString(cookieValue).toString(), false);
            } catch (IllegalArgumentException ignored) {
                // 조작된 값 — 새 키로 시작한다
            }
        }
        return new ActorKey(UUID.randomUUID().toString(), true);
    }

    private static <T> ResponseEntity<T> withActorCookie(ResponseEntity<T> response, ActorKey actorKey) {
        if (!actorKey.issued()) {
            return response;
        }
        ResponseCookie cookie = ResponseCookie.from(ACTOR_COOKIE, actorKey.value())
                .path(ACTOR_COOKIE_PATH)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(ACTOR_MAX_AGE)
                .build();
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response.getBody());
    }
}
