package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.domain.ModelShareComment;
import net.java21.crowfoot.api.model.dto.CreateOwnerShareCommentRequest;
import net.java21.crowfoot.api.model.dto.CreateShareCommentRequest;
import net.java21.crowfoot.api.model.dto.ShareCommentResponse;
import net.java21.crowfoot.api.model.dto.ShareFeedbackResponse;
import net.java21.crowfoot.api.model.dto.ShareReactionResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareCommentQueryRepository;
import net.java21.crowfoot.api.model.repository.ModelShareCommentQueryRepository.CommentRow;
import net.java21.crowfoot.api.model.repository.ModelShareCommentRepository;
import net.java21.crowfoot.api.model.repository.ModelShareReactionRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 공유 문서 피드백 (08-core/02-model.md Section 1.10.6·1.10.7) — 공개 뷰어의 반응 토글·익명 댓글과
 * 에디터 공유 다이얼로그의 오너 답글·댓글 관리. 무인증 경로는 토큰이 자격이고 방문자는
 * crowfoot_share_actor 쿠키(UUID)로 근사 식별한다 — 반응은 UNIQUE(share_id, visitor_key)로
 * 링크당 1방문자 1회, 익명 댓글 삭제는 visitor_key 일치로만.
 * 카운터(reaction_count·comment_count)는 원자 UPDATE로만 갱신한다(읽기-수정-쓰기 금지).
 */
@Service
@RequiredArgsConstructor
public class ShareFeedbackService {

    private final ModelShareRepository shareRepository;
    private final ModelShareReactionRepository reactionRepository;
    private final ModelShareCommentRepository commentRepository;
    private final ModelShareCommentQueryRepository commentQueryRepository;
    private final ModelRepository modelRepository;
    private final UserRepository userRepository;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;

    /** 피드백 초기화(무인증) — 반응 상태 + 댓글 목록을 1회 fetch로. 없는 토큰 404, 기간 밖 410(1.10.4와 동일 판정) */
    @Transactional(readOnly = true)
    public ShareFeedbackResponse listFeedback(String token, String actorKey) {
        ModelShare share = requireActive(token);
        boolean reacted = actorKey != null
                && reactionRepository.existsByShareIdAndVisitorKey(share.getId(), actorKey);
        List<ShareCommentResponse> comments = commentQueryRepository.findByShareIdOrderByIdAsc(share.getId())
                .stream()
                .map(ShareFeedbackService::toResponse)
                .toList();
        return new ShareFeedbackResponse(share.getReactionCount(), reacted, comments);
    }

    /**
     * 반응 토글(무인증, 1.10.6) — 본문 없는 POST 한 번. 삽입 시도(insertIgnoreConflict)가 원자적으로
     * 판정한다: 성공(1)이면 반응 ON(+1), 충돌(0)이면 이미 반응한 방문자라 제거(−1).
     * 같은 방문자의 밀리초 경합은 카운터 표시값이 어긋날 수 있으나(원자 갱신이라 DB는 유지되고 다음 토글로
     * 수렴) 표시 오차로 수용한다 — 조회 수와 같은 부류다.
     */
    @Transactional
    public ShareReactionResponse toggleReaction(String token, String actorKey) {
        ModelShare share = requireActive(token);
        boolean added = reactionRepository.insertIgnoreConflict(share.getId(), actorKey) == 1;
        if (!added) {
            reactionRepository.deleteByShareIdAndVisitorKey(share.getId(), actorKey);
        }
        shareRepository.addReactionCount(share.getId(), added ? 1 : -1);
        auditRecorder.record(null, "SHARE_REACTION_TOGGLED", "SHARE", Long.toString(share.getId()),
                Map.of("added", Boolean.toString(added)));
        return new ShareReactionResponse(share.getReactionCount() + (added ? 1 : -1), added);
    }

    /** 익명 댓글 등록(무인증, 1.10.7) — 별명+내용만, 댓글 수 +1. 감사 actor는 null(익명) */
    @Transactional
    public ShareCommentResponse createComment(String token, String actorKey, CreateShareCommentRequest request) {
        ModelShare share = requireActive(token);
        ModelShareComment comment = commentRepository.save(
                new ModelShareComment(share.getId(), null, request.nickname(), request.content(), actorKey));
        shareRepository.addCommentCount(share.getId(), 1);
        auditRecorder.record(null, "SHARE_COMMENT_CREATED", "SHARE_COMMENT", Long.toString(comment.getId()),
                Map.of("shareId", Long.toString(share.getId()), "anonymous", "true"));
        return toResponse(comment);
    }

    /** 익명 본인 댓글 삭제(무인증, 1.10.7) — 쿠키 visitor_key 일치만. 오너 댓글·타인 댓글은 403 */
    @Transactional
    public void deleteComment(String token, long commentId, String actorKey) {
        ModelShare share = requireActive(token);
        ModelShareComment comment = requireComment(share, commentId);
        if (comment.getAuthorUserId() != null
                || actorKey == null || !actorKey.equals(comment.getVisitorKey())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        long replies = removeComment(share, comment);
        auditRecorder.record(null, "SHARE_COMMENT_DELETED", "SHARE_COMMENT", Long.toString(commentId),
                Map.of("shareId", Long.toString(share.getId()), "withReplies", Long.toString(replies)));
    }

    /**
     * 오너 답글 등록(인증, 1.10.7) — 문서 작성자(model.createdBy) 또는 관리자만. 답글 전용이라
     * parentCommentId는 필수이고, 부모가 답글이면 400(1단계 제한). 응답 nickname은 작성자명.
     */
    @Transactional
    public ShareCommentResponse createOwnerComment(long userId, long workspaceId, long modelId, long shareId,
                                                   CreateOwnerShareCommentRequest request) {
        requireOwnedModel(userId, workspaceId, modelId);
        ModelShare share = shareRepository.findByIdAndModelId(shareId, modelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        ModelShareComment parent = commentRepository.findByIdAndShareId(request.parentCommentId(), share.getId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.share.comment.parent"));
        if (parent.getParentCommentId() != null) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.share.comment.parent");
        }
        ModelShareComment comment = commentRepository.save(
                new ModelShareComment(share.getId(), parent.getId(), request.content(), userId));
        shareRepository.addCommentCount(share.getId(), 1);
        String authorName = userRepository.findById(userId).map(user -> user.getName()).orElse(null);
        auditRecorder.record(userId, "SHARE_COMMENT_CREATED", "SHARE_COMMENT", Long.toString(comment.getId()),
                Map.of("shareId", Long.toString(share.getId()), "anonymous", "false",
                        "parentCommentId", Long.toString(parent.getId())));
        return new ShareCommentResponse(comment.getId().toString(), parent.getId().toString(),
                authorName, comment.getContent(), true, comment.getCreatedAt());
    }

    /** 댓글 관리 삭제(인증, 1.10.7) — 오너·관리자는 그 링크의 모든 댓글·답글 삭제(스팸 대응 주체) */
    @Transactional
    public void deleteOwnerComment(long userId, long workspaceId, long modelId, long shareId, long commentId) {
        requireOwnedModel(userId, workspaceId, modelId);
        ModelShare share = shareRepository.findByIdAndModelId(shareId, modelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        ModelShareComment comment = requireComment(share, commentId);
        long replies = removeComment(share, comment);
        auditRecorder.record(userId, "SHARE_COMMENT_DELETED", "SHARE_COMMENT", Long.toString(commentId),
                Map.of("shareId", Long.toString(share.getId()), "withReplies", Long.toString(replies)));
    }

    /** 원댓글 삭제 — 답글은 FK CASCADE로 동반 삭제되므로 댓글 수도 1+답글 수만큼 내린다. 반환값은 답글 수(감사) */
    private long removeComment(ModelShare share, ModelShareComment comment) {
        long replies = commentRepository.countByParentCommentId(comment.getId());
        commentRepository.delete(comment);
        shareRepository.addCommentCount(share.getId(), -(1 + replies));
        return replies;
    }

    private ModelShareComment requireComment(ModelShare share, long commentId) {
        return commentRepository.findByIdAndShareId(commentId, share.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_COMMENT_NOT_FOUND));
    }

    /** 링크 활성 판정 + 조회(404/410) — 1.10.4 resolve와 같은 규칙 */
    private ModelShare requireActive(String token) {
        ModelShare share = shareRepository.findByShareToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        if (!ShareService.isActive(share, Instant.now())) {
            throw new BusinessException(ErrorCode.SHARE_INACTIVE);
        }
        return share;
    }

    /** 오너 판정은 워크스페이스 역할이 아니라 문서 작성자다 — createdBy 일치 또는 관리자(1.10.7) */
    private Model requireOwnedModel(long userId, long workspaceId, long modelId) {
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
        if (model.getCreatedBy() == null || model.getCreatedBy() != userId) {
            adminGuard.requireAdmin(userId);
        }
        return model;
    }

    private static ShareCommentResponse toResponse(CommentRow row) {
        return new ShareCommentResponse(row.id().toString(),
                row.parentCommentId() == null ? null : row.parentCommentId().toString(),
                row.authorUserId() == null ? row.nickname() : row.authorName(),
                row.content(), row.authorUserId() != null, row.createdAt());
    }

    private static ShareCommentResponse toResponse(ModelShareComment comment) {
        return new ShareCommentResponse(comment.getId().toString(),
                comment.getParentCommentId() == null ? null : comment.getParentCommentId().toString(),
                comment.getNickname(), comment.getContent(), false, comment.getCreatedAt());
    }
}
