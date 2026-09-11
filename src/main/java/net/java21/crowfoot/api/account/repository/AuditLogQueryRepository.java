package net.java21.crowfoot.api.account.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QAuditLog;
import net.java21.crowfoot.api.account.domain.QUser;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 감사 로그 조회(Querydsl) — 08-core/05-account.md Section 2.7.
 * 쓰기(INSERT)는 AuditLogRepository가 담당하고 여기는 읽기 전용이다.
 */
@Repository
@RequiredArgsConstructor
public class AuditLogQueryRepository {

    private final JPAQueryFactory query;

    /**
     * 감사 로그 목록 — 주체(actor) LEFT JOIN, 정렬 id desc(최신순) 고정.
     * keyword는 주체 이름·이메일 부분 일치(대소문자 무시).
     */
    public List<AuditLogRow> searchAuditLogs(String keyword, String action, long offset, int limit) {
        QAuditLog auditLog = QAuditLog.auditLog;
        QUser actor = QUser.user;
        return query
                .select(Projections.constructor(AuditLogRow.class, auditLog.id, auditLog.createdAt,
                        auditLog.actorUserId, actor.name, actor.email,
                        auditLog.action, auditLog.targetType, auditLog.targetId, auditLog.detail, auditLog.ip))
                .from(auditLog)
                .leftJoin(actor).on(auditLog.actorUserId.eq(actor.id))
                .where(actorKeyword(actor, keyword), actionEq(auditLog, action))
                .orderBy(auditLog.id.desc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    public long countAuditLogs(String keyword, String action) {
        QAuditLog auditLog = QAuditLog.auditLog;
        QUser actor = QUser.user;
        Long count = query.select(auditLog.count())
                .from(auditLog)
                .leftJoin(actor).on(auditLog.actorUserId.eq(actor.id))
                .where(actorKeyword(actor, keyword), actionEq(auditLog, action))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /**
     * 주체 검색 키워드 — blank면 조건 없음(전체).
     * 값이 있으면 시스템 행(actor NULL)은 NULL 비교로 자연 제외된다.
     */
    private static BooleanExpression actorKeyword(QUser actor, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return actor.name.containsIgnoreCase(keyword)
                .or(actor.email.containsIgnoreCase(keyword));
    }

    /** 액션 필터 — blank면 조건 없음(전체). 액션 코드는 정확 일치 */
    private static BooleanExpression actionEq(QAuditLog auditLog, String action) {
        return (action == null || action.isBlank()) ? null : auditLog.action.eq(action);
    }

    /** 감사 로그 행 — detail은 JSON 원문(문자열), 객체 파싱은 서비스가 담당 */
    public record AuditLogRow(Long id, Instant createdAt, Long actorUserId, String actorName, String actorEmail,
                              String action, String targetType, String targetId, String detail, String ip) {
    }
}
