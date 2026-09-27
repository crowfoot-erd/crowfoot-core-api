package net.java21.crowfoot.api.model.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QUser;
import net.java21.crowfoot.api.model.domain.QModel;
import net.java21.crowfoot.api.model.domain.QModelComment;
import net.java21.crowfoot.api.model.domain.QModelShare;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 문서 댓글 조회(Querydsl) — 08-core/02-model.md Section 1.10.7·1.10.9.
 * 회원 댓글·오너 답글의 표시명(nickname)은 작성자의 users.name이라 조인으로 실어 온다.
 * 대표 링크 토큰(1.10.9)은 모델 id를 모아 IN 배치 조회로 해결한다 — 행마다 링크를 찾는 N+1 금지
 * (00-environment/code-conventions.md Section 4).
 */
@Repository
@RequiredArgsConstructor
public class ModelCommentQueryRepository {

    private final JPAQueryFactory query;

    /** 댓글 행 — 회원 작성자 이름 조인 포함(비회원은 authorUserId null) */
    public record CommentRow(Long id, Long modelId, Long parentCommentId, String nickname, String content,
                             Long authorUserId, String authorName, Instant createdAt, Instant updatedAt) {
    }

    /** 내 댓글 행(1.10.9) — 댓글에 문서 메타·대표 링크 토큰 조인. 커뮤니티 "내 댓글" 화면 원료 */
    public record MyCommentRow(Long id, Long parentCommentId, String content, Instant createdAt,
                               Instant updatedAt, String shareToken, String modelName, String databaseType) {
    }

    /** 내 댓글 조인 베이스 행 — 대표 링크 토큰을 붙이기 전의 댓글×문서 조인. 클래스 레벨 public이어야
     *  한다: Querydsl 생성자 프로젝션은 getConstructors(public 생성자만)로 대상을 찾아서
     *  메서드 안 로컬 레코드(생성자 비공개)로 두면 런타임에 프로젝션이 실패한다(2026-09-28 500) */
    public record MyCommentBaseRow(Long id, Long parentCommentId, String content, Instant createdAt,
                                   Instant updatedAt, Long modelId, String modelName, String databaseType) {
    }

    /** 문서의 댓글 목록 — id 오름차순(대화 흐름), 페이징 없음 */
    public List<CommentRow> findByModelIdOrderByIdAsc(long modelId) {
        QModelComment comment = QModelComment.modelComment;
        QUser author = QUser.user;
        return query
                .select(Projections.constructor(CommentRow.class, comment.id, comment.modelId,
                        comment.parentCommentId, comment.nickname, comment.content, comment.authorUserId,
                        author.name, comment.createdAt, comment.updatedAt))
                .from(comment)
                .leftJoin(author).on(comment.authorUserId.eq(author.id))
                .where(comment.modelId.eq(modelId))
                .orderBy(comment.id.asc())
                .fetch();
    }

    /** 내가 작성한 댓글(회원·오너 답글 — authorUserId 일치) — 최신 활동순(id desc), 페이징 없음.
     *  문서는 댓글의 FK CASCADE로 항상 함께 살아 있다. shareToken은 문서의 대표 링크(가장 최근 발급),
     *  링크가 하나도 없으면 null(1.10.9 — 링크 철회로 댓글이 사라지지 않는다) */
    public List<MyCommentRow> findByAuthorUserIdOrderByIdDesc(long userId) {
        QModelComment comment = QModelComment.modelComment;
        QModel model = QModel.model;
        List<MyCommentBaseRow> bases = query
                .select(Projections.constructor(MyCommentBaseRow.class, comment.id, comment.parentCommentId,
                        comment.content, comment.createdAt, comment.updatedAt,
                        model.id, model.name, model.databaseType))
                .from(comment)
                .join(model).on(comment.modelId.eq(model.id))
                .where(comment.authorUserId.eq(userId))
                .orderBy(comment.id.desc())
                .fetch();
        Map<Long, String> representativeTokens = representativeTokens(
                bases.stream().map(MyCommentBaseRow::modelId).toList());
        return bases.stream()
                .map(base -> new MyCommentRow(base.id(), base.parentCommentId(), base.content(),
                        base.createdAt(), base.updatedAt(), representativeTokens.get(base.modelId()),
                        base.modelName(), base.databaseType()))
                .toList();
    }

    /** 문서별 대표 링크 토큰 — 최근 발급순으로 받아 모델당 첫 행(=가장 최근 링크)만 남긴다 */
    private Map<Long, String> representativeTokens(List<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        QModelShare share = QModelShare.modelShare;
        return query
                .select(share.modelId, share.shareToken)
                .from(share)
                .where(share.modelId.in(modelIds))
                .orderBy(share.createdAt.desc(), share.id.desc())
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        tuple -> tuple.get(share.modelId),
                        tuple -> tuple.get(share.shareToken),
                        (latest, ignored) -> latest)); // 최근 발급순 정렬이라 첫 등장이 대표 링크
    }
}
