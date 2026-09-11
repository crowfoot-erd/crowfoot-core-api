package net.java21.crowfoot.api.account.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.QWorkspaceMembership;
import net.java21.crowfoot.api.account.domain.QUser;
import net.java21.crowfoot.api.team.domain.QTeamMember;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 사용자 검색(Querydsl) — 후보 검색 응답은 최소 필드로 DTO projection 한다
 * (08-core/03-membership.md Section 1.6 — sub BIGINT 노출 최소화).
 */
@Repository
@RequiredArgsConstructor
public class UserQueryRepository {

    private final JPAQueryFactory query;

    /**
     * Workspace 멤버 부여 후보 — 이름·이메일 부분 일치(대소문자 무시).
     * 탈퇴 사용자·이미 개인 부여된 사용자는 제외한다.
     */
    public List<UserCandidateResponse> searchWorkspaceCandidates(String keyword, Long workspaceId, int limit) {
        QUser user = QUser.user;
        QWorkspaceMembership membership = QWorkspaceMembership.workspaceMembership;
        return query
                .select(Projections.constructor(UserCandidateResponse.class, user.id, user.name, user.email))
                .from(user)
                .where(user.withdrawnAt.isNull(),
                        keywordOr(user, keyword),
                        user.id.notIn(JPAExpressions.select(membership.userId).from(membership)
                                .where(membership.workspaceId.eq(workspaceId),
                                        membership.granteeType.eq(GranteeType.USER),
                                        membership.userId.isNotNull())))
                .orderBy(user.id.asc())
                .limit(limit)
                .fetch();
    }

    /** 팀 멤버 후보 — 이미 팀 멤버인 사용자 제외 (08-core/04-team.md Section 1.6). */
    public List<UserCandidateResponse> searchTeamCandidates(String keyword, Long teamId, int limit) {
        QUser user = QUser.user;
        QTeamMember teamMember = QTeamMember.teamMember;
        return query
                .select(Projections.constructor(UserCandidateResponse.class, user.id, user.name, user.email))
                .from(user)
                .where(user.withdrawnAt.isNull(),
                        keywordOr(user, keyword),
                        user.id.notIn(JPAExpressions.select(teamMember.userId).from(teamMember)
                                .where(teamMember.teamId.eq(teamId))))
                .orderBy(user.id.asc())
                .limit(limit)
                .fetch();
    }

    private static BooleanExpression keywordOr(QUser user, String keyword) {
        return user.name.containsIgnoreCase(keyword)
                .or(user.email.containsIgnoreCase(keyword));
    }

    // ----- 관리자 — 전체 사용자 목록 (08-core/05-account.md Section 2.1) -----

    /** 관리자 검색 — 탈퇴 사용자도 포함(soft 방식, 상태 배지로 구분). 정렬 userId asc 고정 */
    public List<AdminUserRow> searchAdminUsers(String keyword, long offset, int limit) {
        QUser user = QUser.user;
        return query
                .select(Projections.constructor(AdminUserRow.class, user.id, user.email, user.name,
                        user.isAdmin, user.withdrawnAt, user.createdAt))
                .from(user)
                .where(adminKeyword(user, keyword))
                .orderBy(user.id.asc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    public long countAdminUsers(String keyword) {
        QUser user = QUser.user;
        Long count = query.select(user.count())
                .from(user)
                .where(adminKeyword(user, keyword))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 관리자 검색 키워드 — blank면 조건 없음(전체) */
    private static BooleanExpression adminKeyword(QUser user, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keywordOr(user, keyword.trim());
    }

    /** 관리자 목록 조회 행 — providers는 별도 IN 조회로 조립한다 */
    public record AdminUserRow(Long userId, String email, String name,
                               boolean admin, Instant withdrawnAt, Instant createdAt) {
    }

    /** 후보 검색 응답 DTO — 최소 필드 */
    public record UserCandidateResponse(String userId, String name, String email) {

        public UserCandidateResponse(Long userId, String name, String email) {
            this(Long.toString(userId), name, email);
        }
    }
}
