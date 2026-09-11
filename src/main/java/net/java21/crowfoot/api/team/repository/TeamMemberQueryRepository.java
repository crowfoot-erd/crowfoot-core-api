package net.java21.crowfoot.api.team.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.team.domain.QTeamMember;
import net.java21.crowfoot.api.team.domain.TeamMember;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 팀 멤버 조회·집계(Querydsl). */
@Repository
@RequiredArgsConstructor
public class TeamMemberQueryRepository {

    private final JPAQueryFactory query;

    /** 팀 멤버 목록 — joined_at 순(Owner가 초대가 아닌 생성 행이라 자연히 첫 행) */
    public List<TeamMember> findByTeamId(Long teamId) {
        QTeamMember teamMember = QTeamMember.teamMember;
        return query.selectFrom(teamMember)
                .where(teamMember.teamId.eq(teamId))
                .orderBy(teamMember.joinedAt.asc(), teamMember.id.asc())
                .fetch();
    }

    /** 팀 인원 수 — team_members 행 수 */
    public long countByTeamId(Long teamId) {
        QTeamMember teamMember = QTeamMember.teamMember;
        Long count = query.select(teamMember.count())
                .from(teamMember)
                .where(teamMember.teamId.eq(teamId))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 팀 멤버 제외 — 대상 행 삭제 (존재 검사는 서비스가 먼저) */
    public long deleteByTeamIdAndUserId(Long teamId, Long userId) {
        QTeamMember teamMember = QTeamMember.teamMember;
        return query.delete(teamMember)
                .where(teamMember.teamId.eq(teamId), teamMember.userId.eq(userId))
                .execute();
    }
}
