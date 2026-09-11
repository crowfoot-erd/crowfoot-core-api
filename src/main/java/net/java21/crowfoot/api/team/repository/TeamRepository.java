package net.java21.crowfoot.api.team.repository;

import net.java21.crowfoot.api.team.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;

/** 단건 CRUD 전용 — 목록 조회는 TeamQueryRepository(Querydsl)를 사용한다. */
public interface TeamRepository extends JpaRepository<Team, Long> {
}
