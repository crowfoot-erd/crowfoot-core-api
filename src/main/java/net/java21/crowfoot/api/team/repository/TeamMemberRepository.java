package net.java21.crowfoot.api.team.repository;

import net.java21.crowfoot.api.team.domain.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;

/** 단건 CRUD·bulk delete 전용 — 목록·집계는 TeamMemberQueryRepository(Querydsl)를 사용한다. */
public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    boolean existsByTeamIdAndUserId(Long teamId, Long userId);

    /** 팀 해체 — 전원 삭제 */
    long deleteByTeamId(Long teamId);
}
