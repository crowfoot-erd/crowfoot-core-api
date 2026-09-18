package net.java21.crowfoot.api.community.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.community.domain.CommunityBoard;
import net.java21.crowfoot.api.community.domain.CommunityPost;
import net.java21.crowfoot.api.community.dto.CommunityPageParams;
import net.java21.crowfoot.api.community.dto.CommunityPostDetailResponse;
import net.java21.crowfoot.api.community.dto.CommunityPostSummaryResponse;
import net.java21.crowfoot.api.community.dto.CommunityRecentPostResponse;
import net.java21.crowfoot.api.community.dto.CreateCommunityPostRequest;
import net.java21.crowfoot.api.community.dto.UpdateCommunityPostRequest;
import net.java21.crowfoot.api.community.repository.CommunityCommentQueryRepository;
import net.java21.crowfoot.api.community.repository.CommunityPostQueryRepository;
import net.java21.crowfoot.api.community.repository.CommunityPostQueryRepository.PostRow;
import net.java21.crowfoot.api.community.repository.CommunityPostRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 커뮤니티 게시글 API (08-core/08-community.md Section 3) — 목록·최근글·상세·생성·수정·삭제.
 *
 * <p>권한 규칙 — RELEASE_NOTE(릴리스 노트) 작성은 관리자 전용(AdminGuard),
 * 수정·삭제는 작성자 본인 또는 관리자(위반 시 403 PERMISSION_DENIED — 존재 은닉).
 * 워크스페이스 역할(RoleChecker)과 무관한 전역 기능이다.
 */
@Service
@RequiredArgsConstructor
public class CommunityPostService {

    /** 최근글 상한 — 대시보드 위젯 용도를 넘지 않도록 */
    private static final int MAX_RECENT_LIMIT = 20;
    private static final int DEFAULT_RECENT_LIMIT = 5;

    private final CommunityPostRepository communityPostRepository;
    private final CommunityPostQueryRepository communityPostQueryRepository;
    private final CommunityCommentQueryRepository communityCommentQueryRepository;
    private final UserRepository userRepository;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;

    /** 목록 — 게시판 내 최신순(id desc), keyword는 제목 부분 일치 */
    @Transactional(readOnly = true)
    public ListApiResponse<CommunityPostSummaryResponse> list(String boardValue, String keyword,
                                                              Integer page, Integer size) {
        CommunityBoard board = CommunityBoard.fromValue(boardValue);
        String trimmed = keyword == null ? null : keyword.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        CommunityPageParams params = CommunityPageParams.of(page, size);

        long totalCount = communityPostQueryRepository.count(board, trimmed);
        if (totalCount == 0) {
            return ListApiResponse.paged(List.of(), params.page(), params.size(), 0);
        }
        List<PostRow> rows = communityPostQueryRepository.search(board, trimmed, params.offset(), params.size());
        Map<Long, Long> commentCounts = communityCommentQueryRepository.countByPostIds(idsOf(rows));
        List<CommunityPostSummaryResponse> responses = rows.stream()
                .map(row -> toSummary(row, commentCounts.getOrDefault(row.id(), 0L)))
                .toList();
        return ListApiResponse.paged(responses, params.page(), params.size(), totalCount);
    }

    /** 최근글 — 게시판 무관 최신순(대시보드 통합 위젯) */
    @Transactional(readOnly = true)
    public ListApiResponse<CommunityRecentPostResponse> recent(Integer limit) {
        int resolved = (limit == null || limit < 1) ? DEFAULT_RECENT_LIMIT : Math.min(limit, MAX_RECENT_LIMIT);
        List<PostRow> rows = communityPostQueryRepository.recent(resolved);
        Map<Long, Long> commentCounts = communityCommentQueryRepository.countByPostIds(idsOf(rows));
        List<CommunityRecentPostResponse> responses = rows.stream()
                .map(row -> new CommunityRecentPostResponse(row.id().toString(), row.board().name(), row.title(),
                        toAuthor(row), commentCounts.getOrDefault(row.id(), 0L), row.createdAt()))
                .toList();
        return ListApiResponse.of(responses);
    }

    /** 상세 — 마크다운 원문 포함 */
    @Transactional(readOnly = true)
    public CommunityPostDetailResponse detail(long postId) {
        CommunityPost post = requirePost(postId);
        return toDetail(post);
    }

    /** 생성 — RELEASE_NOTE는 관리자만, FEEDBACK은 로그인 사용자 전체 */
    @Transactional
    public CommunityPostDetailResponse create(long userId, CreateCommunityPostRequest request) {
        CommunityBoard board = CommunityBoard.fromValue(request.board());
        if (board == CommunityBoard.RELEASE_NOTE) {
            adminGuard.requireAdmin(userId);
        }
        CommunityPost post = communityPostRepository.save(
                new CommunityPost(board, request.title().trim(), request.content(), userId));
        auditRecorder.record(userId, "COMMUNITY_POST_CREATED", "COMMUNITY_POST", post.getId().toString(),
                Map.of("board", board.name()));
        return toDetail(post);
    }

    /** 수정 — 작성자 본인 또는 관리자, board는 변경 불가(DTO에 없음) */
    @Transactional
    public CommunityPostDetailResponse patch(long userId, long postId, UpdateCommunityPostRequest request) {
        CommunityPost post = requirePost(postId);
        requireAuthorOrAdmin(userId, post.getCreatedBy());
        post.setTitle(request.title().trim());
        post.setContent(request.content());
        auditRecorder.record(userId, "COMMUNITY_POST_UPDATED", "COMMUNITY_POST", post.getId().toString(),
                Map.of("board", post.getBoard().name()));
        return toDetail(post);
    }

    /** 삭제 — 작성자 본인 또는 관리자, 코멘트는 FK CASCADE로 함께 소멸 */
    @Transactional
    public void delete(long userId, long postId) {
        CommunityPost post = requirePost(postId);
        requireAuthorOrAdmin(userId, post.getCreatedBy());
        communityPostRepository.delete(post);
        auditRecorder.record(userId, "COMMUNITY_POST_DELETED", "COMMUNITY_POST", post.getId().toString(),
                Map.of("board", post.getBoard().name()));
    }

    private CommunityPost requirePost(long postId) {
        return communityPostRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
    }

    /** 작성자 본인이면 통과, 아니면 관리자 여부로 판정(AdminGuard가 403을 던진다) */
    private void requireAuthorOrAdmin(long userId, Long createdBy) {
        if (createdBy == null || createdBy != userId) {
            adminGuard.requireAdmin(userId);
        }
    }

    private static List<Long> idsOf(List<PostRow> rows) {
        return rows.stream().map(PostRow::id).toList();
    }

    private CommunityPostSummaryResponse toSummary(PostRow row, long commentCount) {
        return new CommunityPostSummaryResponse(row.id().toString(), row.board().name(), row.title(),
                toAuthor(row), commentCount, row.createdAt(), row.updatedAt());
    }

    /** 상세 응답 조립 — 생성·수정 직후 엔티티에는 작성자 이름이 없어 별도 조회(FK로 항상 존재) */
    private CommunityPostDetailResponse toDetail(CommunityPost post) {
        String authorName = userRepository.findById(post.getCreatedBy())
                .map(user -> user.getName())
                .orElse(null);
        return new CommunityPostDetailResponse(post.getId().toString(), post.getBoard().name(), post.getTitle(),
                post.getContent(), new UserRefResponse(post.getCreatedBy().toString(), authorName),
                post.getCreatedAt(), post.getUpdatedAt());
    }

    private static UserRefResponse toAuthor(PostRow row) {
        return new UserRefResponse(row.createdBy() == null ? null : row.createdBy().toString(), row.authorName());
    }
}
