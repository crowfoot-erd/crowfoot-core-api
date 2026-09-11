package net.java21.crowfoot.api.team.repository;

import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.team.domain.QTeam;
import net.java21.crowfoot.api.team.domain.QTeamMember;
import net.java21.crowfoot.api.team.domain.Team;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 팀 목록 조회(Querydsl). */
@Repository
@RequiredArgsConstructor
public class TeamQueryRepository {

    private final JPAQueryFactory query;

    /** 내가 소속된 팀 전체 — 소속(team_members) 기준, Owner 포함 */
    public List<Team> findJoinedByUserId(Long userId) {
        QTeam team = QTeam.team;
        QTeamMember teamMember = QTeamMember.teamMember;
        return query.selectFrom(team)
                .where(team.id.in(JPAExpressions.select(teamMember.teamId)
                        .from(teamMember)
                        .where(teamMember.userId.eq(userId))))
                .orderBy(team.id.asc())
                .fetch();
    }

    /** 내가 Owner인 팀만 — Workspace 팀 부여 대상 선택용 */
    public List<Team> findOwnedByUserId(Long userId) {
        QTeam team = QTeam.team;
        return query.selectFrom(team)
                .where(team.ownerUserId.eq(userId))
                .orderBy(team.id.asc())
                .fetch();
    }

    /** 회원 탈퇴 사전 조건 — 본인이 Owner인 팀이 존재하는지 (08-core/05-account.md Section 1.3) */
    public boolean existsOwnedTeam(Long userId) {
        QTeam team = QTeam.team;
        return query.selectOne()
                .from(team)
                .where(team.ownerUserId.eq(userId))
                .fetchFirst() != null;
    }
}
