package net.java21.crowfoot.api.workspace.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QRole;
import net.java21.crowfoot.api.account.domain.RoleCode;
import net.java21.crowfoot.api.team.domain.QTeamMember;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.QWorkspaceMembership;
import net.java21.crowfoot.api.workspace.domain.WorkspaceMembership;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 멤버십 조회·집계(Querydsl) — 유효 역할(max 합산) 판정을 포함한다.
 * 조인은 workspace_memberships × roles(비연관, role 컬럼) — 전부 core 자기 DB다.
 */
@Repository
@RequiredArgsConstructor
public class WorkspaceMembershipQueryRepository {

    private final JPAQueryFactory query;

    /**
     * 유효 역할 — 개인 직접 부여와 소속 팀 부여를 합산해 level 최대인 부여
     * (02-auth/requirements.md Section 1.2). 멤버가 아니면 empty.
     */
    public Optional<EffectiveRole> findEffectiveRole(Long userId, Long workspaceId) {
        QWorkspaceMembership membership = QWorkspaceMembership.workspaceMembership;
        QRole role = QRole.role;
        QTeamMember teamMember = QTeamMember.teamMember;
        return Optional.ofNullable(query
                .select(Projections.constructor(EffectiveRole.class, role.code, role.level))
                .from(membership)
                .innerJoin(role).on(role.code.eq(membership.role.stringValue()))
                .where(membership.workspaceId.eq(workspaceId),
                        grantTo(userId, membership, teamMember))
                .orderBy(role.level.desc())
                .limit(1)
                .fetchOne());
    }

    /** 사용자가 접근 가능한 워크스페이스 id — 내 워크스페이스 목록(1회 조합)의 시작점 */
    public List<Long> findWorkspaceIdsOfUser(Long userId) {
        QWorkspaceMembership membership = QWorkspaceMembership.workspaceMembership;
        QTeamMember teamMember = QTeamMember.teamMember;
        return query.select(membership.workspaceId).distinct()
                .from(membership)
                .where(grantTo(userId, membership, teamMember))
                .orderBy(membership.workspaceId.asc())
                .fetch();
    }

    /** 부여 행 목록 — 멤버 관리 탭 (08-core/03-membership.md Section 1.2) */
    public List<WorkspaceMembership> findByWorkspaceId(Long workspaceId) {
        QWorkspaceMembership membership = QWorkspaceMembership.workspaceMembership;
        return query.selectFrom(membership)
                .where(membership.workspaceId.eq(workspaceId))
                .orderBy(membership.id.asc())
                .fetch();
    }

    /**
     * 유효 멤버 인원 수 — 개인 부여 사용자와 팀 부여 팀의 멤버를 합집합한 distinct 인원 수
     * (08-core/01-workspace.md — memberCount 정의, 부여 행 수 아님).
     */
    public long countDistinctMembers(Long workspaceId) {
        QWorkspaceMembership membership = QWorkspaceMembership.workspaceMembership;
        QTeamMember teamMember = QTeamMember.teamMember;

        List<Long> direct = query.select(membership.userId)
                .from(membership)
                .where(membership.workspaceId.eq(workspaceId),
                        membership.granteeType.eq(GranteeType.USER),
                        membership.userId.isNotNull())
                .fetch();
        List<Long> viaTeams = query.select(teamMember.userId)
                .from(teamMember)
                .where(teamMember.teamId.in(JPAExpressions.select(membership.teamId)
                        .from(membership)
                        .where(membership.workspaceId.eq(workspaceId),
                                membership.granteeType.eq(GranteeType.TEAM))))
                .fetch();

        Set<Long> members = new HashSet<>(direct);
        members.addAll(viaTeams);
        return members.size();
    }

    /** 마지막 Owner 보호 검사용 — 해당 Workspace의 OWNER 부여 행 수 */
    public long countOwnerGrants(Long workspaceId) {
        QWorkspaceMembership membership = QWorkspaceMembership.workspaceMembership;
        return query.select(membership.count())
                .from(membership)
                .where(membership.workspaceId.eq(workspaceId), membership.role.eq(RoleCode.OWNER))
                .fetchOne();
    }

    /**
     * 회원 탈퇴 사전 조건 — 본인이 OWNER인 멤버십의 Workspace에 본인 외 부여가 남아 있는지
     * (08-core/05-account.md Section 1.3).
     */
    public long countOtherGrantsInOwnerWorkspaces(Long userId) {
        QWorkspaceMembership membership = QWorkspaceMembership.workspaceMembership;
        List<Long> ownedWorkspaceIds = query.select(membership.workspaceId)
                .from(membership)
                .where(membership.granteeType.eq(GranteeType.USER),
                        membership.userId.eq(userId),
                        membership.role.eq(RoleCode.OWNER))
                .fetch();
        if (ownedWorkspaceIds.isEmpty()) {
            return 0;
        }
        Long count = query.select(membership.count())
                .from(membership)
                .where(membership.workspaceId.in(ownedWorkspaceIds),
                        selfGrant(userId, membership).not())
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 이미 부여된 (workspace, grantee) 조합 검사 — 동시 부여 방어 */
    public boolean existsGrant(Long workspaceId, GranteeType granteeType, Long userId, Long teamId) {
        QWorkspaceMembership membership = QWorkspaceMembership.workspaceMembership;
        return query.selectOne()
                .from(membership)
                .where(membership.workspaceId.eq(workspaceId),
                        membership.granteeType.eq(granteeType),
                        granteeType == GranteeType.USER
                                ? membership.userId.eq(userId)
                                : membership.teamId.eq(teamId))
                .fetchFirst() != null;
    }

    /** userId가 받은 부여(개인 직접 + 소속 팀) 조건 */
    private static BooleanExpression grantTo(Long userId, QWorkspaceMembership membership,
                                             QTeamMember teamMember) {
        BooleanExpression directGrant = membership.granteeType.eq(GranteeType.USER)
                .and(membership.userId.eq(userId));
        BooleanExpression teamGrant = membership.granteeType.eq(GranteeType.TEAM)
                .and(membership.teamId.in(JPAExpressions.select(teamMember.teamId)
                        .from(teamMember)
                        .where(teamMember.userId.eq(userId))));
        return directGrant.or(teamGrant);
    }

    private static BooleanExpression selfGrant(Long userId, QWorkspaceMembership membership) {
        return membership.granteeType.eq(GranteeType.USER).and(membership.userId.eq(userId));
    }

    /** 유효 역할 값 — code는 부여 코드(예: EDITOR), level은 roles 테이블의 권한 서열 */
    public record EffectiveRole(String code, int level) {
    }
}
