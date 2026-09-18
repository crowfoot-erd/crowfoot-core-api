package net.java21.crowfoot.api.community.controller;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.community.dto.CommunityPostDetailResponse;
import net.java21.crowfoot.api.community.dto.CommunityRecentPostResponse;
import net.java21.crowfoot.api.community.service.CommunityPostService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 릴리스 노트 공개 조회 API (08-core/08-community.md Section 3.11) — 무인증
 * (XUserIdFilter 제외 경로, Gateway 화이트리스트). 랜딩 페이지·공개 뷰어가 호출한다.
 *
 * <p>RELEASE_NOTE 게시판의 읽기만 노출한다 — FEEDBACK 게시판은 로그인 사용자 전용이므로
 * 이 경로에서는 존재 자체를 은닉한다(다른 게시판의 post-id는 404 COMMUNITY_POST_NOT_FOUND).
 * 쓰기(생성·수정·삭제·코멘트)는 기존 인증 경로(/core/community/posts**)를 그대로 쓴다.
 */
@RestController
@RequiredArgsConstructor
public class CommunityReleaseNoteController {

    private final CommunityPostService communityPostService;

    /** 공개 최근 릴리스 노트 — RELEASE_NOTE만 최신순, 기본 5건(랜딩 위젯) */
    @GetMapping("/core/community/release-notes/recent")
    public ListApiResponse<CommunityRecentPostResponse> recent(
            @RequestParam(name = "limit", required = false) Integer limit) {
        return communityPostService.recentReleaseNotes(limit);
    }

    /** 공개 릴리스 노트 상세 — 마크다운 원문 포함, RELEASE_NOTE가 아니면 404(존재 은닉) */
    @GetMapping("/core/community/release-notes/{post-id}")
    public ApiResponse<CommunityPostDetailResponse> detail(@PathVariable("post-id") long postId) {
        return ApiResponse.success(communityPostService.releaseNoteDetail(postId));
    }
}
