package net.java21.crowfoot.api.notification.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QUser;
import net.java21.crowfoot.api.model.domain.QModel;
import net.java21.crowfoot.api.notification.domain.QNotification;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 알림 목록 조회(Querydsl) — 08-core/11-notification.md Section 5.
 * actor 표시명(회원 users.name)과 문서의 워크스페이스(웹 링크용 workspaceId)를 조인으로 실어 온다 —
 * 행마다 users를 찾는 N+1 금지(00-environment/code-conventions.md Section 4).
 */
@Repository
@RequiredArgsConstructor
public class NotificationQueryRepository {

    private final JPAQueryFactory query;

    /** 알림 행 — actor 표시명(회원 name·게스트 별명 스냅샷 조합은 서비스가 한다)과 문서 워크스페이스 포함.
     *  클래스 레벨 public이어야 한다: Querydsl 생성자 프로젝션은 getConstructors(public 생성자만)로
     *  대상을 찾아서 메서드 안 로컬 레코드로 두면 런타임에 프로젝션이 실패한다(model 댓글 2026-09-28 500) */
    public record NotificationRow(Long id, String type, Long actorUserId, String actorName,
                                  String actorNickname, Long modelId, String modelName,
                                  Long workspaceId, Instant readAt, Instant createdAt) {
    }

    /** 수신자의 알림 목록 — 최신순(id desc) 오프셋 페이징. 문서는 FK CASCADE로 항상 함께 살아 있다 */
    public List<NotificationRow> findByUserIdOrderByIdDesc(long userId, long offset, int limit) {
        QNotification notification = QNotification.notification;
        QUser actor = QUser.user;
        QModel model = QModel.model;
        return query
                .select(Projections.constructor(NotificationRow.class, notification.id, notification.type,
                        notification.actorUserId, actor.name, notification.actorNickname,
                        notification.modelId, model.name, model.workspaceId,
                        notification.readAt, notification.createdAt))
                .from(notification)
                .leftJoin(actor).on(notification.actorUserId.eq(actor.id))
                .join(model).on(notification.modelId.eq(model.id))
                .where(notification.userId.eq(userId))
                .orderBy(notification.id.desc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    /** 수신자의 알림 총수 — 페이징 totalCount */
    public long countByUserId(long userId) {
        QNotification notification = QNotification.notification;
        Long count = query.select(notification.count())
                .from(notification)
                .where(notification.userId.eq(userId))
                .fetchOne();
        return count == null ? 0 : count;
    }
}
