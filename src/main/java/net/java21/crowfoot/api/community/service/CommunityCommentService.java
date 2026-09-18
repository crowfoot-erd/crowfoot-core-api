package net.java21.crowfoot.api.community.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.community.domain.CommunityBoard;
import net.java21.crowfoot.api.community.domain.CommunityComment;
import net.java21.crowfoot.api.community.domain.CommunityPost;
import net.java21.crowfoot.api.community.dto.CommunityCommentResponse;
import net.java21.crowfoot.api.community.dto.CreateCommunityCommentRequest;
import net.java21.crowfoot.api.community.dto.UpdateCommunityCommentRequest;
import net.java21.crowfoot.api.community.repository.CommunityCommentQueryRepository;
import net.java21.crowfoot.api.community.repository.CommunityCommentQueryRepository.CommentRow;
import net.java21.crowfoot.api.community.repository.CommunityCommentRepository;
import net.java21.crowfoot.api.community.repository.CommunityPostRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 커뮤니티 코멘트 API (08-core/08-community.md Section 3) — FEEDBACK(제안 및 신고) 게시글 전용.
 * 릴리스 노트는 읽기 전용이라 생성·목록 모두 400 COMMUNITY_COMMENT_NOT_ALLOWED로 차단한다.
 * 수정·삭제는 작성자 본인 또는 관리자.
 */
@Service
@RequiredArgsConstructor
public class CommunityCommentService {

    private final CommunityCommentRepository communityCommentRepository;
    private final CommunityCommentQueryRepository communityCommentQueryRepository;
    private final CommunityPostRepository communityPostRepository;
    private final UserRepository userRepository;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;

    /** 목록 — 오래된 순(대화 흐름), 페이징 없음(게시글당 규모가 작다 — 멤버 목록 관례) */
    @Transactional(readOnly = true)
    public ListApiResponse<CommunityCommentResponse> list(long postId) {
        CommunityPost post = requirePost(postId);
        requireFeedbackBoard(post);
        List<CommunityCommentResponse> responses = communityCommentQueryRepository.findByPostId(postId).stream()
                .map(CommunityCommentService::toResponse)
                .toList();
        return ListApiResponse.of(responses);
    }

    /** 생성 — FEEDBACK 게시글에만 허용, 로그인 사용자 누구나 */
    @Transactional
    public CommunityCommentResponse create(long userId, long postId, CreateCommunityCommentRequest request) {
        CommunityPost post = requirePost(postId);
        requireFeedbackBoard(post);
        CommunityComment comment = communityCommentRepository.save(
                new CommunityComment(postId, request.content(), userId));
        auditRecorder.record(userId, "COMMUNITY_COMMENT_CREATED", "COMMUNITY_COMMENT", comment.getId().toString(),
                Map.of("postId", postId));
        return toResponse(comment, userId, authorName(userId));
    }

    /** 수정 — 작성자 본인 또는 관리자 */
    @Transactional
    public CommunityCommentResponse patch(long userId, long commentId, UpdateCommunityCommentRequest request) {
        CommunityComment comment = requireComment(commentId);
        requireAuthorOrAdmin(userId, comment.getCreatedBy());
        comment.setContent(request.content());
        auditRecorder.record(userId, "COMMUNITY_COMMENT_UPDATED", "COMMUNITY_COMMENT", comment.getId().toString(),
                Map.of("postId", comment.getPostId()));
        return toResponse(comment, comment.getCreatedBy(), authorName(comment.getCreatedBy()));
    }

    /** 삭제 — 작성자 본인 또는 관리자 */
    @Transactional
    public void delete(long userId, long commentId) {
        CommunityComment comment = requireComment(commentId);
        requireAuthorOrAdmin(userId, comment.getCreatedBy());
        communityCommentRepository.delete(comment);
        auditRecorder.record(userId, "COMMUNITY_COMMENT_DELETED", "COMMUNITY_COMMENT", comment.getId().toString(),
                Map.of("postId", comment.getPostId()));
    }

    private CommunityPost requirePost(long postId) {
        return communityPostRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
    }

    private CommunityComment requireComment(long commentId) {
        return communityCommentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    /** 릴리스 노트 차단 — 읽기 전용 게시판 */
    private static void requireFeedbackBoard(CommunityPost post) {
        if (post.getBoard() != CommunityBoard.FEEDBACK) {
            throw new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_ALLOWED);
        }
    }

    private void requireAuthorOrAdmin(long userId, Long createdBy) {
        if (createdBy == null || createdBy != userId) {
            adminGuard.requireAdmin(userId);
        }
    }

    private String authorName(long userId) {
        return userRepository.findById(userId).map(user -> user.getName()).orElse(null);
    }

    private static CommunityCommentResponse toResponse(CommentRow row) {
        return new CommunityCommentResponse(row.id().toString(), row.postId().toString(), row.content(),
                new UserRefResponse(row.createdBy() == null ? null : row.createdBy().toString(), row.authorName()),
                row.createdAt(), row.updatedAt());
    }

    private CommunityCommentResponse toResponse(CommunityComment comment, long actorUserId, String authorName) {
        return new CommunityCommentResponse(comment.getId().toString(), comment.getPostId().toString(),
                comment.getContent(), new UserRefResponse(Long.toString(actorUserId), authorName),
                comment.getCreatedAt(), comment.getUpdatedAt());
    }
}
