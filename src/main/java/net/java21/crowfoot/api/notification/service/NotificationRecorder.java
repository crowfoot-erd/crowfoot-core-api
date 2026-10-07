package net.java21.crowfoot.api.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelComment;
import net.java21.crowfoot.api.notification.domain.Notification;
import net.java21.crowfoot.api.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 알림 발행 파사드 (08-core/11-notification.md Section 3) — 피드백 이벤트를 수신자별 알림으로 남긴다.
 * 발행 실패가 본류를 실패시키지 않는다(best-effort — AuditRecorder와 같은 2계층).
 *
 * <p>쓰기는 {@link NotificationWriter}의 별도 트랜잭션(REQUIRES_NEW)으로 수행한다 — 본류 롤백과
 * 무관하게 남고, catch는 트랜잭션 경계 밖(여기)에 둔다. 경계 안에서 삼키면 rollback-only 커밋이
 * UnexpectedRollbackException으로 새어나가 본류를 죽인다. 스킵 판정(자기 행위·게스트 수신 불가·
 * 좋아요 재토글 억제)도 여기서 한다 — 훅(ShareFeedbackService)은 이벤트 사실만 전달한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRecorder {

    private final NotificationRepository notificationRepository;
    private final NotificationWriter notificationWriter;
    private final net.java21.crowfoot.api.account.repository.UserRepository userRepository;

    /** 내 문서에 원댓글(COMMENT_CREATED) — 수신자 = 문서 오너. 게스트 댓글은 별명 스냅샷으로 남는다 */
    public void notifyCommentCreated(Model model, Long authorUserId, String guestNickname) {
        try {
            Long receiver = model.getCreatedBy();
            if (receiver == null || (authorUserId != null && authorUserId.equals(receiver))) {
                return; // 방어(오너 부재)·오너의 자기 문서 댓글 — 알림 없음
            }
            notificationWriter.insert(new Notification(receiver, Notification.TYPE_COMMENT_CREATED,
                    authorUserId, guestNickname, model.getId(), model.getName()));
        } catch (Exception ex) {
            log.warn("알림 발행 실패(type=COMMENT_CREATED, model={}) — 본류에는 영향 없음: {}",
                    model.getId(), ex.getMessage());
        }
    }

    /** 내 문서 좋아요(REACTION_ADDED, 토글 on만) — 수신자 = 문서 오너. 재토글(on→off→on)은 억제한다 */
    public void notifyReactionAdded(Model model, long actorUserId) {
        try {
            Long receiver = model.getCreatedBy();
            if (receiver == null || receiver == actorUserId) {
                return; // 오너의 자기 문서 좋아요 — 알림 없음
            }
            if (notificationRepository.existsByUserIdAndTypeAndActorUserIdAndModelId(
                    receiver, Notification.TYPE_REACTION_ADDED, actorUserId, model.getId())) {
                return; // 같은 회원의 같은 문서 좋아요 알림이 이미 있으면 만들지 않는다(읽음 여부 무관)
            }
            notificationWriter.insert(new Notification(receiver, Notification.TYPE_REACTION_ADDED,
                    actorUserId, null, model.getId(), model.getName()));
        } catch (Exception ex) {
            log.warn("알림 발행 실패(type=REACTION_ADDED, model={}) — 본류에는 영향 없음: {}",
                    model.getId(), ex.getMessage());
        }
    }

    /** 내 "제안 및 신고" 글에 댓글(COMMUNITY_COMMENT_CREATED, Section 2.1) — 수신자 = 게시글 작성자.
     *  자기 글에 단 자기 댓글은 알리지 않는다. 제목은 이벤트 시점 스냅샷(200자) */
    public void notifyCommunityCommentCreated(long postId, Long postAuthorId, String postTitle, long commenterId,
                                              long commentId) {
        afterCommit(() -> writeCommunityCommentCreated(postId, postAuthorId, postTitle, commenterId, commentId));
    }

    private void writeCommunityCommentCreated(long postId, Long postAuthorId, String postTitle, long commenterId,
                                              long commentId) {
        try {
            if (postAuthorId == null || postAuthorId == commenterId) {
                return;
            }
            String title = postTitle == null ? "" : postTitle.length() > 200 ? postTitle.substring(0, 200) : postTitle;
            notificationWriter.insert(Notification.forPost(postAuthorId, Notification.TYPE_COMMUNITY_COMMENT_CREATED,
                    commenterId, postId, title, commentId));
        } catch (Exception ex) {
            log.warn("알림 발행 실패(type=COMMUNITY_COMMENT_CREATED, post={}) — 본류에는 영향 없음: {}",
                    postId, ex.getMessage());
        }
    }

    /** "제안 및 신고" 새 글(FEEDBACK_POST_CREATED, Section 2.2) — 수신자 = 탈퇴하지 않은 관리자 전원.
     *  작성자가 관리자면 본인은 빼고 보낸다. 수신자마다 한 행(각자 읽음 처리) */
    public void notifyFeedbackPostCreated(long postId, String postTitle, long authorId) {
        afterCommit(() -> writeFeedbackPostCreated(postId, postTitle, authorId));
    }

    private void writeFeedbackPostCreated(long postId, String postTitle, long authorId) {
        try {
            String title = postTitle == null ? "" : postTitle.length() > 200 ? postTitle.substring(0, 200) : postTitle;
            for (Long adminId : userRepository.findActiveAdminIds()) {
                if (adminId == null || adminId == authorId) {
                    continue;
                }
                notificationWriter.insert(Notification.forPost(adminId, Notification.TYPE_FEEDBACK_POST_CREATED,
                        authorId, postId, title, null));
            }
        } catch (Exception ex) {
            log.warn("알림 발행 실패(type=FEEDBACK_POST_CREATED, post={}) — 본류에는 영향 없음: {}",
                    postId, ex.getMessage());
        }
    }

    /** 내 원댓글에 오너 답글(OWNER_REPLIED) — 수신자 = 원댓글 작성자. 게스트 원댓글은 수신자가 없다 */
    public void notifyOwnerReplied(Model model, ModelComment parent, long replierUserId) {
        try {
            Long receiver = parent.getAuthorUserId();
            if (receiver == null || receiver == replierUserId) {
                return; // 게스트 원댓글(수신 불가)·자기 원댓글에 단 자기 답글
            }
            notificationWriter.insert(new Notification(receiver, Notification.TYPE_OWNER_REPLIED,
                    replierUserId, null, model.getId(), model.getName()));
        } catch (Exception ex) {
            log.warn("알림 발행 실패(type=OWNER_REPLIED, model={}) — 본류에는 영향 없음: {}",
                    model.getId(), ex.getMessage());
        }
    }

    /**
     * 본류 트랜잭션이 커밋된 뒤에 기록한다 — 커뮤니티 알림은 같은 트랜잭션에서 방금 만든 게시글·댓글을 외래 키로
     * 가리킨다(fk_notifications_post_id·comment_id). 알림은 별도 트랜잭션(REQUIRES_NEW)이라 커밋 전에는 그 행이
     * 보이지 않아 외래 키 위반으로 실패했고, best-effort라 경고 로그만 남은 채 관리자 알림이 오지 않았다(v1.37 사용자 보고).
     * 본류가 롤백되면 알림도 남기지 않는다. 트랜잭션 밖에서 부르면 바로 기록한다
     */
    private static void afterCommit(Runnable write) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            write.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                write.run();
            }
        });
    }
}
