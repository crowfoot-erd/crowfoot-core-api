package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelComment;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.dto.CreateModelCommentRequest;
import net.java21.crowfoot.api.model.dto.CreateShareCommentRequest;
import net.java21.crowfoot.api.model.dto.MyShareCommentResponse;
import net.java21.crowfoot.api.model.dto.MyShareReactionResponse;
import net.java21.crowfoot.api.model.dto.ShareCommentResponse;
import net.java21.crowfoot.api.model.dto.ShareFeedbackResponse;
import net.java21.crowfoot.api.model.dto.ShareReactionResponse;
import net.java21.crowfoot.api.model.dto.UpdateShareCommentRequest;
import net.java21.crowfoot.api.model.repository.ModelCommentQueryRepository;
import net.java21.crowfoot.api.model.repository.ModelCommentQueryRepository.CommentRow;
import net.java21.crowfoot.api.model.repository.ModelCommentRepository;
import net.java21.crowfoot.api.model.repository.ModelReactionQueryRepository;
import net.java21.crowfoot.api.model.repository.ModelReactionRepository;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 문서 피드백 (08-core/02-model.md Section 1.10.6·1.10.7) — 반응(회원전용 토글)과
 * 댓글(회원·비회원·오너 답글). **스레드는 문서(model) 단위다(2026-09-28 재설계)** — 공개 토큰 경로와
 * 멤버 문서 경로가 같은 스레드를 읽고 쓴다. 두 경로가 같은 판정 로직을 공유한다:
 * 토큰 경로는 토큰→문서 해석(404/410) 뒤, 멤버 경로는 워크스페이스 멤버 판정(읽기·반응은 역할 무관,
 * 댓글 작성은 Commenter 이상) 뒤에 문서 단위로 기록한다.
 * 댓글 경로(토큰)는 선택 인증이라 userId 유무로 모드를 가른다: 회원 댓글은 계정 판정(본인만 수정·삭제),
 * 비회원 댓글은 작성 시 설정한 비밀번호(PBKDF2 해시) 판정. 반응 신원은 계정 user_id —
 * UNIQUE(model_id, user_id)로 문서당 1회원 1회. 카운터(reaction_count·comment_count, models)는
 * 원자 UPDATE로만 갱신한다(읽기-수정-쓰기 금지).
 */
@Service
@RequiredArgsConstructor
public class ShareFeedbackService {

    private final ModelShareRepository shareRepository;
    private final ModelReactionRepository reactionRepository;
    private final ModelCommentRepository commentRepository;
    private final ModelCommentQueryRepository commentQueryRepository;
    private final ModelReactionQueryRepository reactionQueryRepository;
    private final ModelRepository modelRepository;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final GuestPasswordHasher passwordHasher;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;

    // ---- 공개 토큰 경로(선택 인증·회원전용 반응) — 토큰→문서 해석 뒤 문서 스레드에 기록한다 ----

    /** 피드백 초기화(선택 인증) — 반응 상태 + 댓글 목록을 1회 fetch로. 없는 토큰 404, 기간 밖 410(1.10.4와 동일 판정) */
    @Transactional(readOnly = true)
    public ShareFeedbackResponse listFeedback(String token, Long userId) {
        Model model = requireActiveModel(token);
        return feedbackOf(model, userId);
    }

    /**
     * 반응 토글(회원전용, 1.10.6) — 본문 없는 POST 한 번. 삽입 시도(insertIgnoreConflict)가 원자적으로
     * 판정한다: 성공(1)이면 반응 ON(+1), 충돌(0)이면 이미 반응한 회원이라 제거(−1).
     * 같은 회원의 밀리초 경합은 카운터 표시값이 어긋날 수 있으나(원자 갱신이라 DB는 유지되고 다음 토글로
     * 수렴) 표시 오차로 수용한다 — 조회 수와 같은 부류다.
     */
    @Transactional
    public ShareReactionResponse toggleReaction(String token, long userId) {
        return toggleModelReaction(requireActiveModel(token), userId);
    }

    /**
     * 댓글 등록(선택 인증, 1.10.7) — 회원 요청(userId 있음)은 계정 댓글로, 비회원 요청은
     * 별명+비밀번호 검증을 거쳐 비회원 댓글로. 댓글 수 +1. 답글은 멤버 문서 경로(오너) 전용이다.
     */
    @Transactional
    public ShareCommentResponse createComment(String token, Long userId, CreateShareCommentRequest request) {
        Model model = requireActiveModel(token);
        ModelComment comment;
        String authorType;
        if (userId != null) {
            comment = commentRepository.save(new ModelComment(model.getId(), null, request.content(), userId));
            authorType = authorTypeOf(userId, model.getCreatedBy());
        } else {
            if (isBlank(request.nickname()) || isBlank(request.password())) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.share.comment.guest-required");
            }
            comment = commentRepository.save(new ModelComment(model.getId(), request.nickname(),
                    request.content(), passwordHasher.hash(request.password())));
            authorType = "guest";
        }
        modelRepository.addCommentCount(model.getId(), 1);
        auditRecorder.record(userId, "MODEL_COMMENT_CREATED", "MODEL_COMMENT", Long.toString(comment.getId()),
                Map.of("modelId", Long.toString(model.getId()), "authorType", authorType));
        return toResponse(comment, displayNameOf(comment), authorType, false);
    }

    /** 댓글 수정(선택 인증, 1.10.7) — 회원은 본인 계정 판정, 비회원은 비밀번호 일치. 본문만 고친다(edited=true) */
    @Transactional
    public ShareCommentResponse updateComment(String token, long commentId, Long userId,
                                              UpdateShareCommentRequest request) {
        Model model = requireActiveModel(token);
        ModelComment comment = requireComment(model, commentId);
        requireAuthor(comment, userId, request.password());
        comment.setContent(request.content());
        comment = commentRepository.save(comment);
        auditRecorder.record(userId, "MODEL_COMMENT_UPDATED", "MODEL_COMMENT", Long.toString(commentId),
                Map.of("modelId", Long.toString(model.getId())));
        return toResponse(comment, displayNameOf(comment), authorTypeOf(comment, model.getCreatedBy()), true);
    }

    /** 댓글 삭제(선택 인증, 1.10.7) — 판정은 수정과 같다. 원댓글이면 답글 수만큼 댓글 수도 내린다(CASCADE 동반) */
    @Transactional
    public void deleteComment(String token, long commentId, Long userId, String password) {
        Model model = requireActiveModel(token);
        ModelComment comment = requireComment(model, commentId);
        requireAuthor(comment, userId, password);
        long replies = removeComment(model, comment);
        auditRecorder.record(userId, "MODEL_COMMENT_DELETED", "MODEL_COMMENT", Long.toString(commentId),
                Map.of("modelId", Long.toString(model.getId()), "withReplies", Long.toString(replies)));
    }

    // ---- 멤버 문서 경로(인증 — 문서 열기 댓글 탭) — 오너 답글·관리 삭제를 포함한다 ----

    /** 댓글 목록 + 반응 상태(멤버면 역할 무관) — 토큰 경로와 같은 형태의 문서 스레드 */
    @Transactional(readOnly = true)
    public ShareFeedbackResponse listModelFeedback(long userId, long workspaceId, long modelId) {
        roleChecker.requireMember(userId, workspaceId);
        Model model = requireModelInWorkspace(workspaceId, modelId);
        return feedbackOf(model, userId);
    }

    /** 반응 토글(멤버면 역할 무관 — VIEWER도 좋아요는 가능, 댓글 작성 권한과 구분한다) */
    @Transactional
    public ShareReactionResponse toggleModelReaction(long userId, long workspaceId, long modelId) {
        roleChecker.requireMember(userId, workspaceId);
        return toggleModelReaction(requireModelInWorkspace(workspaceId, modelId), userId);
    }

    /**
     * 멤버 댓글 등록(Commenter 이상, 1.10.7) — 회원 전용 경로라 nickname·password는 무시되고 계정 댓글로
     * 등록된다(authorType=member, 문서 작성자 본인이면 owner). parentCommentId 지정은 오너 답글이다 —
     * 문서 작성자·관리자만 가능하고 그 외 멤버는 403, 부모가 답글이면 400(1단계 제한).
     */
    @Transactional
    public ShareCommentResponse createModelComment(long userId, long workspaceId, long modelId,
                                                   CreateModelCommentRequest request) {
        roleChecker.requireCommenter(userId, workspaceId);
        Model model = requireModelInWorkspace(workspaceId, modelId);
        Long parentCommentId = request.parentCommentId();
        if (parentCommentId == null) {
            ModelComment comment = commentRepository.save(
                    new ModelComment(model.getId(), null, request.content(), userId));
            modelRepository.addCommentCount(model.getId(), 1);
            auditRecorder.record(userId, "MODEL_COMMENT_CREATED", "MODEL_COMMENT",
                    Long.toString(comment.getId()), Map.of(
                            "modelId", Long.toString(model.getId()),
                            "authorType", authorTypeOf(userId, model.getCreatedBy())));
            return toResponse(comment, displayNameOf(comment), authorTypeOf(userId, model.getCreatedBy()), false);
        }
        requireModelOwner(userId, model);
        ModelComment parent = commentRepository.findByIdAndModelId(parentCommentId, model.getId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.share.comment.parent"));
        if (parent.getParentCommentId() != null) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.share.comment.parent");
        }
        ModelComment comment = commentRepository.save(
                new ModelComment(model.getId(), parent.getId(), request.content(), userId));
        modelRepository.addCommentCount(model.getId(), 1);
        Map<String, Object> details = new HashMap<>();
        details.put("modelId", Long.toString(model.getId()));
        details.put("authorType", "owner");
        details.put("parentCommentId", Long.toString(parent.getId()));
        auditRecorder.record(userId, "MODEL_COMMENT_CREATED", "MODEL_COMMENT",
                Long.toString(comment.getId()), details);
        return toResponse(comment, displayNameOf(comment), "owner", false);
    }

    /** 멤버 댓글 수정(Commenter 이상, 1.10.7) — 본인 댓글만(오너·관리자도 남의 글을 수정할 수는 없다) */
    @Transactional
    public ShareCommentResponse updateModelComment(long userId, long workspaceId, long modelId, long commentId,
                                                   UpdateShareCommentRequest request) {
        roleChecker.requireCommenter(userId, workspaceId);
        Model model = requireModelInWorkspace(workspaceId, modelId);
        ModelComment comment = requireComment(model, commentId);
        if (comment.getAuthorUserId() == null || userId != comment.getAuthorUserId()) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        comment.setContent(request.content());
        comment = commentRepository.save(comment);
        auditRecorder.record(userId, "MODEL_COMMENT_UPDATED", "MODEL_COMMENT", Long.toString(commentId),
                Map.of("modelId", Long.toString(model.getId())));
        return toResponse(comment, displayNameOf(comment), authorTypeOf(comment, model.getCreatedBy()), true);
    }

    /**
     * 멤버 댓글 삭제(Commenter 이상, 1.10.7) — 본인 댓글에 더해 오너(문서 작성자)·관리자는 문서의
     * 모든 댓글·답글을 지운다(스팸 대응 주체). 원댓글이면 답글 수만큼 댓글 수도 내린다.
     */
    @Transactional
    public void deleteModelComment(long userId, long workspaceId, long modelId, long commentId) {
        roleChecker.requireCommenter(userId, workspaceId);
        Model model = requireModelInWorkspace(workspaceId, modelId);
        ModelComment comment = requireComment(model, commentId);
        boolean author = comment.getAuthorUserId() != null && userId == comment.getAuthorUserId();
        if (!author) {
            requireModelOwner(userId, model); // 오너·관리자만 남의 댓글 삭제 가능
        }
        long replies = removeComment(model, comment);
        auditRecorder.record(userId, "MODEL_COMMENT_DELETED", "MODEL_COMMENT", Long.toString(commentId),
                Map.of("modelId", Long.toString(model.getId()), "withReplies", Long.toString(replies)));
    }

    // ---- 내 피드백 역조회(1.10.9) ----

    /**
     * 내가 작성한 문서 댓글(인증, 1.10.9) — 회원 댓글·오너 답글만(비회원은 신원이 없다).
     * 최신 활동순, 페이징 없음. 링크를 철회해도 댓글은 살아 있다(문서 소속 — 2026-09-28).
     */
    @Transactional(readOnly = true)
    public List<MyShareCommentResponse> myComments(long userId) {
        return commentQueryRepository.findByAuthorUserIdOrderByIdDesc(userId).stream()
                .map(row -> new MyShareCommentResponse(row.id().toString(),
                        row.parentCommentId() == null ? null : row.parentCommentId().toString(),
                        row.content(), !row.updatedAt().equals(row.createdAt()), row.createdAt(),
                        row.shareToken(), row.modelName(), row.databaseType()))
                .toList();
    }

    /** 내가 좋아요한 문서(인증, 1.10.9) — 최근 반응순. 토글 제거한 문서는 행이 없어 자동 제외 */
    @Transactional(readOnly = true)
    public List<MyShareReactionResponse> myReactions(long userId) {
        return reactionQueryRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(row -> new MyShareReactionResponse(row.reactedAt(), row.shareToken(), row.modelName(),
                        row.description(), row.databaseType(), row.viewCount(), row.reactionCount(),
                        row.commentCount()))
                .toList();
    }

    // ---- 공통 ----

    /** 피드백 래퍼 조립 — 두 경로(토큰·멤버)가 같은 형태로 내려준다 */
    private ShareFeedbackResponse feedbackOf(Model model, Long userId) {
        boolean reacted = userId != null
                && reactionRepository.existsByModelIdAndUserId(model.getId(), userId);
        List<ShareCommentResponse> comments = commentQueryRepository.findByModelIdOrderByIdAsc(model.getId())
                .stream()
                .map(row -> toResponse(row, model.getCreatedBy()))
                .toList();
        return new ShareFeedbackResponse(model.getReactionCount(), reacted, comments);
    }

    /** 반응 토글 공통 — 문서 단위 UNIQUE 판정과 카운터 원자 갱신, 감사 MODEL_REACTION_TOGGLED */
    private ShareReactionResponse toggleModelReaction(Model model, long userId) {
        boolean added = reactionRepository.insertIgnoreConflict(model.getId(), userId) == 1;
        if (!added) {
            reactionRepository.deleteByModelIdAndUserId(model.getId(), userId);
        }
        modelRepository.addReactionCount(model.getId(), added ? 1 : -1);
        auditRecorder.record(userId, "MODEL_REACTION_TOGGLED", "MODEL", Long.toString(model.getId()),
                Map.of("added", Boolean.toString(added)));
        return new ShareReactionResponse(model.getReactionCount() + (added ? 1 : -1), added);
    }

    /** 수정·삭제 판정(토큰 경로) — 회원 댓글은 authorUserId 일치, 비회원 댓글은 비밀번호 해시 일치. 어느 쪽 아니면 403 */
    private void requireAuthor(ModelComment comment, Long userId, String password) {
        if (comment.getAuthorUserId() != null) {
            if (userId == null || userId.longValue() != comment.getAuthorUserId()) {
                throw BusinessException.of(ErrorCode.PERMISSION_DENIED, "detail.share.comment.password");
            }
            return;
        }
        if (!passwordHasher.matches(password, comment.getPasswordHash())) {
            throw BusinessException.of(ErrorCode.PERMISSION_DENIED, "detail.share.comment.password");
        }
    }

    /** 원댓글 삭제 — 답글은 FK CASCADE로 동반 삭제되므로 댓글 수도 1+답글 수만큼 내린다. 반환값은 답글 수(감사) */
    private long removeComment(Model model, ModelComment comment) {
        long replies = commentRepository.countByParentCommentId(comment.getId());
        commentRepository.delete(comment);
        modelRepository.addCommentCount(model.getId(), -(1 + replies));
        return replies;
    }

    private ModelComment requireComment(Model model, long commentId) {
        return commentRepository.findByIdAndModelId(commentId, model.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_COMMENT_NOT_FOUND));
    }

    /** 토큰→문서 해석 + 링크 활성 판정(404/410) — 1.10.4 resolve와 같은 규칙. 문서가 없으면(방어) 404 */
    private Model requireActiveModel(String token) {
        ModelShare share = shareRepository.findByShareToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        if (!ShareService.isActive(share, Instant.now())) {
            throw new BusinessException(ErrorCode.SHARE_INACTIVE);
        }
        return modelRepository.findById(share.getModelId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
    }

    /** 멤버 경로의 문서 확보 — 워크스페이스 경계 안 문서(존재 은닉 404) */
    private Model requireModelInWorkspace(long workspaceId, long modelId) {
        return modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
    }

    /** 오너 판정은 워크스페이스 역할이 아니라 문서 작성자다 — createdBy 일치 또는 관리자(1.10.7) */
    private void requireModelOwner(long userId, Model model) {
        if (model.getCreatedBy() == null || model.getCreatedBy() != userId) {
            adminGuard.requireAdmin(userId);
        }
    }

    private static String authorTypeOf(Long authorUserId, Long ownerUserId) {
        if (authorUserId == null) {
            return "guest";
        }
        return authorUserId.equals(ownerUserId) ? "owner" : "member";
    }

    private static String authorTypeOf(ModelComment comment, Long ownerUserId) {
        return authorTypeOf(comment.getAuthorUserId(), ownerUserId);
    }

    private static ShareCommentResponse toResponse(CommentRow row, Long ownerUserId) {
        return new ShareCommentResponse(row.id().toString(),
                row.parentCommentId() == null ? null : row.parentCommentId().toString(),
                row.authorUserId() == null ? row.nickname() : row.authorName(),
                authorTypeOf(row.authorUserId(), ownerUserId), row.content(),
                !row.updatedAt().equals(row.createdAt()), row.createdAt());
    }

    private static ShareCommentResponse toResponse(ModelComment comment, String displayName,
                                                   String authorType, boolean edited) {
        return new ShareCommentResponse(comment.getId().toString(),
                comment.getParentCommentId() == null ? null : comment.getParentCommentId().toString(),
                displayName, authorType, comment.getContent(), edited, comment.getCreatedAt());
    }

    /** 저장 직후 응답의 표시명 — 비회원은 저장된 별명, 회원은 users.name(없으면 null — 방어) */
    private String displayNameOf(ModelComment comment) {
        if (comment.getAuthorUserId() == null) {
            return comment.getNickname();
        }
        return userRepository.findById(comment.getAuthorUserId()).map(user -> user.getName()).orElse(null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
