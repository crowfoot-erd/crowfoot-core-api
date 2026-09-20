package net.java21.crowfoot.api.model.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QUser;
import net.java21.crowfoot.api.model.domain.QModelVersion;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 문서 버전 기록 목록 조회(Querydsl) — 08-core/02-model.md Section 1.11 (최신순·페이징). */
@Repository
@RequiredArgsConstructor
public class ModelVersionQueryRepository {

    private final JPAQueryFactory query;

    /** 목록 행 — content(최대 5MB)를 제외한 요약 + 기록자 이름(LEFT JOIN — 프론트 별도 조회 방지) */
    public record VersionRow(
            long version,
            String changeSummary,
            String memo,
            Long createdById,
            String createdByName,
            Instant createdAt
    ) {
    }

    /** 버전 기록 목록 — 정렬 version desc(최신 먼저). keyword는 메모 부분 일치(대소문자 무시) */
    public List<VersionRow> search(long modelId, String keyword, int page, int size) {
        QModelVersion version = QModelVersion.modelVersion;
        QUser creator = QUser.user;
        return query.select(Projections.constructor(VersionRow.class,
                        version.version, version.changeSummary, version.memo,
                        version.createdBy, creator.name, version.createdAt))
                .from(version)
                .leftJoin(creator).on(version.createdBy.eq(creator.id))
                .where(version.modelId.eq(modelId), memoLike(keyword))
                .orderBy(version.version.desc())
                .offset((long) (page - 1) * size)
                .limit(size)
                .fetch();
    }

    /** 검색 조건에 맞는 총 스냅샷 수 */
    public long count(long modelId, String keyword) {
        QModelVersion version = QModelVersion.modelVersion;
        return query.select(version.count())
                .from(version)
                .where(version.modelId.eq(modelId), memoLike(keyword))
                .fetchFirst();
    }

    /** 메모 검색 조건 — null이면 조건 없음(전체). memo가 NULL인 행은 키워드 지정 시 제외된다 */
    private static BooleanExpression memoLike(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return QModelVersion.modelVersion.memo.containsIgnoreCase(keyword);
    }
}
