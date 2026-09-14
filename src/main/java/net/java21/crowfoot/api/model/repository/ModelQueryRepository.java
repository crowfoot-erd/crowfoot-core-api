package net.java21.crowfoot.api.model.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QUser;
import net.java21.crowfoot.api.model.domain.QModel;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 모델 목록 조회(Querydsl) — 08-core/02-model.md Section 1.1 (keyword·페이징·updatedAt desc). */
@Repository
@RequiredArgsConstructor
public class ModelQueryRepository {

    private final JPAQueryFactory query;

    /** 목록 행 — content 제외 요약 + 생성자 이름(LEFT JOIN — 프론트 별도 조회 방지) */
    public record ModelRow(
            Long id,
            Long workspaceId,
            String name,
            String description,
            String databaseType,
            long version,
            Long createdById,
            String createdByName,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /** 모델 목록 — keyword는 이름·설명 부분 일치(대소문자 무시), 정렬 updatedAt desc */
    public List<ModelRow> search(long workspaceId, String keyword, int page, int size) {
        QModel model = QModel.model;
        QUser creator = QUser.user;
        return query.select(Projections.constructor(ModelRow.class,
                        model.id, model.workspaceId, model.name, model.description,
                        model.databaseType, model.version,
                        model.createdBy, creator.name, model.createdAt, model.updatedAt))
                .from(model)
                .leftJoin(creator).on(model.createdBy.eq(creator.id))
                .where(workspaceEq(workspaceId), keywordLike(keyword))
                .orderBy(model.updatedAt.desc(), model.id.desc())
                .offset((long) (page - 1) * size)
                .limit(size)
                .fetch();
    }

    /** 검색 조건에 맞는 총 개수 */
    public long count(long workspaceId, String keyword) {
        QModel model = QModel.model;
        return query.select(model.count())
                .from(model)
                .where(workspaceEq(workspaceId), keywordLike(keyword))
                .fetchFirst();
    }

    private static BooleanExpression workspaceEq(long workspaceId) {
        return QModel.model.workspaceId.eq(workspaceId);
    }

    private static BooleanExpression keywordLike(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        QModel model = QModel.model;
        return model.name.containsIgnoreCase(keyword)
                .or(model.description.containsIgnoreCase(keyword));
    }
}
