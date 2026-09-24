package net.java21.crowfoot.api.term.repository;

import net.java21.crowfoot.api.term.domain.SystemTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 시스템 사전 리포지토리 (08-core/01-workspace.md Section 4.5) — 전역 자원이라 소속 조건이 없다.
 *  페이징·검색 목록은 SystemTermQueryRepository(Querydsl)가 맡는다 */
public interface SystemTermRepository extends JpaRepository<SystemTerm, Long> {

    /** 전역 자연키 조회 — upsert 판정 */
    Optional<SystemTerm> findByTerm(String term);
}
