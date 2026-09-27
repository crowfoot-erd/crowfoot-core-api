package net.java21.crowfoot.api.model.repository;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.model.domain.QModel;
import net.java21.crowfoot.api.model.domain.QModelReaction;
import net.java21.crowfoot.api.model.domain.QModelShare;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 문서 반응 조회(Querydsl) — 08-core/02-model.md Section 1.10.9.
 * 내 반응(좋아요) 목록에 문서 메타·카운터(문서 단위 — 2026-09-28)를 조인해 실어 온다 —
 * 커뮤니티 "좋아요 문서" 화면 원료. 대표 링크(토큰·조회 수)는 모델 id를 모아 IN 배치 조회로
 * 해결한다 — 행마다 링크를 찾는 N+1 금지(00-environment/code-conventions.md Section 4).
 */
@Repository
@RequiredArgsConstructor
public class ModelReactionQueryRepository {

    private final JPAQueryFactory query;

    /** 내 반응 행 — 좋아요 시각 + 갤러리 카드와 같은 문서 메타·카운터. 링크가 없으면 shareToken null·viewCount 0 */
    public record MyReactionRow(Instant reactedAt, String shareToken, String modelName, String description,
                                String databaseType, long viewCount, long reactionCount, long commentCount) {
    }

    /** 내 반응 조인 베이스 행 — 대표 링크(토큰·조회 수)를 붙이기 전의 반응×문서 조인. 클래스 레벨
     *  public이어야 한다: Querydsl 생성자 프로젝션은 getConstructors(public 생성자만)로 대상을
     *  찾아서 메서드 안 로컬 레코드(생성자 비공개)로 두면 런타임에 프로젝션이 실패한다(2026-09-28 500) */
    public record MyReactionBaseRow(Instant reactedAt, Long modelId, String modelName, String description,
                                    String databaseType, long reactionCount, long commentCount) {
    }

    /** 내가 좋아요한 문서 — 최근 반응순(id desc), 페이징 없음. 문서는 반응의 FK CASCADE로 항상 함께 살아 있다 */
    public List<MyReactionRow> findByUserIdOrderByIdDesc(long userId) {
        QModelReaction reaction = QModelReaction.modelReaction;
        QModel model = QModel.model;
        List<MyReactionBaseRow> bases = query
                .select(Projections.constructor(MyReactionBaseRow.class, reaction.createdAt, model.id, model.name,
                        model.description, model.databaseType, model.reactionCount, model.commentCount))
                .from(reaction)
                .join(model).on(reaction.modelId.eq(model.id))
                .where(reaction.userId.eq(userId))
                .orderBy(reaction.id.desc())
                .fetch();
        Map<Long, Tuple> representatives = representativeShares(
                bases.stream().map(MyReactionBaseRow::modelId).toList());
        QModelShare share = QModelShare.modelShare;
        return bases.stream()
                .map(base -> {
                    Tuple representative = representatives.get(base.modelId());
                    return new MyReactionRow(base.reactedAt(),
                            representative == null ? null : representative.get(share.shareToken),
                            base.modelName(), base.description(), base.databaseType(),
                            representative == null ? 0L : representative.get(share.viewCount),
                            base.reactionCount(), base.commentCount());
                })
                .toList();
    }

    /** 문서별 대표 링크(토큰·조회 수) — 최근 발급순으로 받아 모델당 첫 행(=가장 최근 링크)만 남긴다 */
    private Map<Long, Tuple> representativeShares(List<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        QModelShare share = QModelShare.modelShare;
        return query
                .select(share.modelId, share.shareToken, share.viewCount)
                .from(share)
                .where(share.modelId.in(modelIds))
                .orderBy(share.createdAt.desc(), share.id.desc())
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        tuple -> tuple.get(share.modelId),
                        tuple -> tuple,
                        (latest, ignored) -> latest)); // 최근 발급순 정렬이라 첫 등장이 대표 링크
    }
}
