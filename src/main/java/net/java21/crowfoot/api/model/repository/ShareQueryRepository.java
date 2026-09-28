package net.java21.crowfoot.api.model.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.model.domain.QModel;
import net.java21.crowfoot.api.model.domain.QModelShare;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 공유 링크 공개 조회(Querydsl) — 08-core/02-model.md Section 1.10.10 (사이트맵 원료) */
@Repository
@RequiredArgsConstructor
public class ShareQueryRepository {

    private final JPAQueryFactory query;

    /** 사이트맵 행 — 색인 URL 원료만 담는다(본문·카운터 없음). 클래스 레벨 public — Projections 대상 */
    public record SitemapRow(String shareToken, Instant lastmod) {
    }

    /**
     * 사이트맵 원료 — 활성 링크의 문서당 **최신 링크** 토큰(시작 전·종료 후 제외, 1.10과 같은 기간
     * 판정), 문서 갱신순(updatedAt desc). 갤러리(1.10.5)의 "문서당 최근 링크 1개"와 같은 규칙을
     * 저장소에서 notExists 상관 서브쿼리로 판정한다 — 전체 링크를 메모리로 읽지 않는다.
     */
    public List<SitemapRow> findSitemapShares(Instant now, int limit) {
        QModelShare share = QModelShare.modelShare;
        QModelShare newer = new QModelShare("newer");
        QModel model = QModel.model;
        return query.select(Projections.constructor(SitemapRow.class, share.shareToken, model.updatedAt))
                .from(share)
                .join(model).on(share.modelId.eq(model.id))
                .where(active(share, now), JPAExpressions
                        .selectOne()
                        .from(newer)
                        .where(newer.modelId.eq(share.modelId), newer.id.gt(share.id), active(newer, now))
                        .notExists())
                .orderBy(model.updatedAt.desc(), share.id.desc())
                .limit(limit)
                .fetch();
    }

    /** 링크 기간 판정(ShareService.isActive와 같은 규칙) — 시작일 null은 즉시, 종료일 null은 무제한 */
    private static BooleanExpression active(QModelShare share, Instant now) {
        return share.startsAt.isNull().or(share.startsAt.loe(now))
                .and(share.endsAt.isNull().or(share.endsAt.goe(now)));
    }
}
