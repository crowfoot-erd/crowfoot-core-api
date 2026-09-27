package net.java21.crowfoot.api.model.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QUser;
import net.java21.crowfoot.api.model.domain.QModelShareComment;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 공유 문서 댓글 조회(Querydsl) — 08-core/02-model.md Section 1.10.7.
 * 오너 답글의 표시명(nickname)은 문서 작성자의 users.name이라 조인으로 실어 온다.
 */
@Repository
@RequiredArgsConstructor
public class ModelShareCommentQueryRepository {

    private final JPAQueryFactory query;

    /** 댓글 행 — 오너 답글 작성자 이름 조인 포함(익명은 authorUserId null) */
    public record CommentRow(Long id, Long shareId, Long parentCommentId, String nickname, String content,
                             Long authorUserId, String authorName, Instant createdAt) {
    }

    /** 링크의 댓글 목록 — id 오름차순(대화 흐름), 페이징 없음 */
    public List<CommentRow> findByShareIdOrderByIdAsc(long shareId) {
        QModelShareComment comment = QModelShareComment.modelShareComment;
        QUser author = QUser.user;
        return query
                .select(Projections.constructor(CommentRow.class, comment.id, comment.shareId,
                        comment.parentCommentId, comment.nickname, comment.content, comment.authorUserId,
                        author.name, comment.createdAt))
                .from(comment)
                .leftJoin(author).on(comment.authorUserId.eq(author.id))
                .where(comment.shareId.eq(shareId))
                .orderBy(comment.id.asc())
                .fetch();
    }
}
