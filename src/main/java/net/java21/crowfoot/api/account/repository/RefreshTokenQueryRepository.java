package net.java21.crowfoot.api.account.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QRefreshToken;
import net.java21.crowfoot.api.account.domain.RefreshToken;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Refresh 저장소 조회·폐기(Querydsl) — 폐기는 bulk update(이미 폐기된 행은 건드리지 않는다·멱등). */
@Repository
@RequiredArgsConstructor
public class RefreshTokenQueryRepository {

    private final JPAQueryFactory query;

    /** 세션 lineage의 현재 활성 Refresh — GRACE 판정의 latestJti */
    public Optional<RefreshToken> findLatestActive(UUID sessionId) {
        QRefreshToken token = QRefreshToken.refreshToken;
        return Optional.ofNullable(query.selectFrom(token)
                .where(token.sessionId.eq(sessionId),
                        token.revokedAt.isNull(),
                        token.rotatedAt.isNull())
                .orderBy(token.issuedAt.desc())
                .limit(1)
                .fetchOne());
    }

    /** 세션 lineage 전량 폐기 — 세션 무효화(재사용 감지) */
    public long revokeBySessionId(UUID sessionId, Instant now) {
        QRefreshToken token = QRefreshToken.refreshToken;
        return query.update(token)
                .set(token.revokedAt, now)
                .where(token.sessionId.eq(sessionId), token.revokedAt.isNull())
                .execute();
    }

    /** 사용자 lineage 전량 폐기 — 회원 탈퇴 */
    public long revokeAllByUserId(Long userId, Instant now) {
        QRefreshToken token = QRefreshToken.refreshToken;
        return query.update(token)
                .set(token.revokedAt, now)
                .where(token.userId.eq(userId), token.revokedAt.isNull())
                .execute();
    }

    /** 폐기 전 활성 세션 sid 목록 — 탈퇴 시 인증 서버 블랙리스트 등록 대상 */
    public List<UUID> findActiveSessionIds(Long userId) {
        QRefreshToken token = QRefreshToken.refreshToken;
        return query.select(token.sessionId).distinct()
                .from(token)
                .where(activeByUser(token, userId))
                .fetch();
    }

    // ----- 관리자 — 활성 세션 목록 (08-core/05-account.md Section 2.3) -----

    /** 활성 lineage가 존재하는 sid 수 — 세션 목록 totalCount */
    public long countActiveSessions(Long userId) {
        QRefreshToken token = QRefreshToken.refreshToken;
        Long count = query.select(token.sessionId.countDistinct())
                .from(token)
                .where(activeByUser(token, userId))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 활성 세션 페이지 — sid + 최초 발급(MIN(issued_at)). 최근 로그인 순(최초 발급 desc) */
    public List<SessionRow> findActiveSessionPage(Long userId, long offset, int limit) {
        QRefreshToken token = QRefreshToken.refreshToken;
        return query
                .select(Projections.constructor(SessionRow.class, token.sessionId, token.issuedAt.min()))
                .from(token)
                .where(activeByUser(token, userId))
                .groupBy(token.sessionId)
                .orderBy(token.issuedAt.min().desc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    /** sid들의 활성 행 전건(issued_at asc) — 마지막 사용·ip·User-Agent 집계 원천 */
    public List<RefreshToken> findActiveRowsBySessionIds(List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        QRefreshToken token = QRefreshToken.refreshToken;
        return query.selectFrom(token)
                .where(token.sessionId.in(sessionIds), token.revokedAt.isNull())
                .orderBy(token.issuedAt.asc())
                .fetch();
    }

    /** 활성 lineage 존재 검사 — 관리자 폐기 대상 판정(없으면 404 SESSION_NOT_FOUND) */
    public boolean existsActiveSession(UUID sessionId) {
        QRefreshToken token = QRefreshToken.refreshToken;
        return query.selectOne()
                .from(token)
                .where(token.sessionId.eq(sessionId), token.revokedAt.isNull())
                .limit(1)
                .fetchFirst() != null;
    }

    private static BooleanExpression activeByUser(QRefreshToken token, Long userId) {
        return token.userId.eq(userId)
                .and(token.revokedAt.isNull());
    }

    /** 활성 세션 행 — sid별 최초 발급 시각(세션 createdAt) */
    public record SessionRow(UUID sessionId, Instant firstIssuedAt) {
    }
}
