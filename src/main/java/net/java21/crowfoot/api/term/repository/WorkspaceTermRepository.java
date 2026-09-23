package net.java21.crowfoot.api.term.repository;

import net.java21.crowfoot.api.term.domain.WorkspaceTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 용어 사전 리포지토리 — 워크스페이스 하위 자원이라 소속 조건이 항상 붙는다 (08-core/01-workspace.md Section 4) */
public interface WorkspaceTermRepository extends JpaRepository<WorkspaceTerm, Long> {

    /** 목록 — term 오름차순(사전 순). 추론 사전을 편집 UI에도 같은 순서로 보여준다 */
    List<WorkspaceTerm> findByWorkspaceIdOrderByTermAsc(long workspaceId);

    /** 자연키 조회 — upsert 판정 */
    Optional<WorkspaceTerm> findByWorkspaceIdAndTerm(long workspaceId, String term);

    /** 워크스페이스별 상한(1,000개) 검사 — 신규 등록 시에만 센다 */
    long countByWorkspaceId(long workspaceId);
}
