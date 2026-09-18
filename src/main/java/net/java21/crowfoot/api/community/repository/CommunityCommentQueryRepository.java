package net.java21.crowfoot.api.community.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QUser;
import net.java21.crowfoot.api.community.domain.QCommunityComment;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 커뮤니티 코멘트 조회(Querydsl) — 08-core/08-community.md Section 3.
 * 게시글별 건수는 목록 페이지의 post-id 집합에 대해 grouped count 1회로 합산한다(N+1 회피).
 */
@Repository
@RequiredArgsConstructor
public class CommunityCommentQueryRepository {

    private final JPAQueryFactory query;

    /** 코멘트 행 — 작성자 이름 조인 포함 */
    public record CommentRow(Long id, Long postId, String content, Long createdBy, String authorName,
                             Instant createdAt, Instant updatedAt) {
    }

    /** 게시글 코멘트 목록 — 오래된 순(대화 흐름) */
    public List<CommentRow> findByPostId(long postId) {
        QCommunityComment comment = QCommunityComment.communityComment;
        QUser author = QUser.user;
        return query
                .select(Projections.constructor(CommentRow.class, comment.id, comment.postId, comment.content,
                        comment.createdBy, author.name, comment.createdAt, comment.updatedAt))
                .from(comment)
                .leftJoin(author).on(comment.createdBy.eq(author.id))
                .where(comment.postId.eq(postId))
                .orderBy(comment.id.asc())
                .fetch();
    }

    /** post-id 집합별 코멘트 건수 — 빈 집합은 조인 없이 빈 맵 */
    public Map<Long, Long> countByPostIds(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        QCommunityComment comment = QCommunityComment.communityComment;
        return query
                .select(comment.postId, comment.count())
                .from(comment)
                .where(comment.postId.in(postIds))
                .groupBy(comment.postId)
                .fetch()
                .stream()
                .collect(Collectors.toMap(tuple -> tuple.get(0, Long.class), tuple -> tuple.get(1, Long.class)));
    }
}
