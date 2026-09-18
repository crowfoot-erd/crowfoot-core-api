package net.java21.crowfoot.api.community.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.community.dto.CommunityPostDetailResponse;
import net.java21.crowfoot.api.community.dto.CommunityPostSummaryResponse;
import net.java21.crowfoot.api.community.dto.CommunityRecentPostResponse;
import net.java21.crowfoot.api.community.dto.CreateCommunityPostRequest;
import net.java21.crowfoot.api.community.dto.UpdateCommunityPostRequest;
import net.java21.crowfoot.api.community.service.CommunityPostService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 커뮤니티 게시글 API (08-core/08-community.md Section 3) — 구현 경로 /core/**
 * (Gateway URL Rewrite 후 — 외부 계약은 /api/v1/core/*). 목록·최근글·상세·생성·수정·삭제.
 */
@RestController
@RequiredArgsConstructor
public class CommunityPostController {

    private final CommunityPostService communityPostService;

    /** 게시판별 목록 — board 필수, keyword는 제목 검색, page 1부터 */
    @GetMapping("/core/community/posts")
    public ListApiResponse<CommunityPostSummaryResponse> list(
            @RequestParam(name = "board") String board,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return communityPostService.list(board, keyword, page, size);
    }

    /** 최근글(대시보드 통합 위젯) — 게시판 무관 최신순, 기본 5건 */
    @GetMapping("/core/community/posts/recent")
    public ListApiResponse<CommunityRecentPostResponse> recent(
            @RequestParam(name = "limit", required = false) Integer limit) {
        return communityPostService.recent(limit);
    }

    /** 상세 — 마크다운 원문 포함 */
    @GetMapping("/core/community/posts/{post-id}")
    public ApiResponse<CommunityPostDetailResponse> detail(@PathVariable("post-id") long postId) {
        return ApiResponse.success(communityPostService.detail(postId));
    }

    /** 생성 — RELEASE_NOTE는 관리자만(서비스 판정), FEEDBACK은 로그인 사용자 전체 */
    @PostMapping("/core/community/posts")
    public ResponseEntity<ApiResponse<CommunityPostDetailResponse>> create(
            @Valid @RequestBody CreateCommunityPostRequest request) {
        CommunityPostDetailResponse response = communityPostService.create(CurrentUserHolder.get().userId(), request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/community/posts/" + response.postId()))
                .body(ApiResponse.success(response));
    }

    /** 수정 — 작성자 본인 또는 관리자, board는 변경 불가 */
    @PatchMapping("/core/community/posts/{post-id}")
    public ApiResponse<CommunityPostDetailResponse> patch(
            @PathVariable("post-id") long postId,
            @Valid @RequestBody UpdateCommunityPostRequest request) {
        return ApiResponse.success(communityPostService.patch(CurrentUserHolder.get().userId(), postId, request));
    }

    /** 삭제 — 작성자 본인 또는 관리자, 코멘트도 함께 소멸(FK CASCADE), 본문 없음 */
    @DeleteMapping("/core/community/posts/{post-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("post-id") long postId) {
        communityPostService.delete(CurrentUserHolder.get().userId(), postId);
    }
}
