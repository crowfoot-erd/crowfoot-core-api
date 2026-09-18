package net.java21.crowfoot.api.community.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.community.dto.CommunityCommentResponse;
import net.java21.crowfoot.api.community.dto.CreateCommunityCommentRequest;
import net.java21.crowfoot.api.community.dto.UpdateCommunityCommentRequest;
import net.java21.crowfoot.api.community.service.CommunityCommentService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 커뮤니티 코멘트 API (08-core/08-community.md Section 3) — 구현 경로 /core/**
 * FEEDBACK(제안 및 신고) 게시글 전용 — 릴리스 노트는 읽기 전용이라 400으로 차단된다.
 */
@RestController
@RequiredArgsConstructor
public class CommunityCommentController {

    private final CommunityCommentService communityCommentService;

    /** 코멘트 목록 — 오래된 순, 페이징 없음 */
    @GetMapping("/core/community/posts/{post-id}/comments")
    public ListApiResponse<CommunityCommentResponse> list(@PathVariable("post-id") long postId) {
        return communityCommentService.list(postId);
    }

    /** 생성 — FEEDBACK 게시글에만 허용 */
    @PostMapping("/core/community/posts/{post-id}/comments")
    public ResponseEntity<ApiResponse<CommunityCommentResponse>> create(
            @PathVariable("post-id") long postId,
            @Valid @RequestBody CreateCommunityCommentRequest request) {
        CommunityCommentResponse response =
                communityCommentService.create(CurrentUserHolder.get().userId(), postId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/community/comments/" + response.commentId()))
                .body(ApiResponse.success(response));
    }

    /** 수정 — 작성자 본인 또는 관리자 */
    @PatchMapping("/core/community/comments/{comment-id}")
    public ApiResponse<CommunityCommentResponse> patch(
            @PathVariable("comment-id") long commentId,
            @Valid @RequestBody UpdateCommunityCommentRequest request) {
        return ApiResponse.success(
                communityCommentService.patch(CurrentUserHolder.get().userId(), commentId, request));
    }

    /** 삭제 — 작성자 본인 또는 관리자, 본문 없음 */
    @DeleteMapping("/core/community/comments/{comment-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("comment-id") long commentId) {
        communityCommentService.delete(CurrentUserHolder.get().userId(), commentId);
    }
}
