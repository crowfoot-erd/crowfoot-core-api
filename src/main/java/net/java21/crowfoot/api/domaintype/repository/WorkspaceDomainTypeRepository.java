package net.java21.crowfoot.api.domaintype.repository;

import net.java21.crowfoot.api.domaintype.domain.WorkspaceDomainType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 도메인 타입 리포지토리 — 워크스페이스 하위 자원이라 소속 조건이 항상 붙는다 (08-core/16-domain-type.md) */
public interface WorkspaceDomainTypeRepository extends JpaRepository<WorkspaceDomainType, Long> {

    /** 목록 — 이름 오름차순(대소문자 무시) */
    List<WorkspaceDomainType> findByWorkspaceIdOrderByNameAsc(long workspaceId);

    /** 이름 중복 판정 — 대소문자를 무시한다 */
    Optional<WorkspaceDomainType> findByWorkspaceIdAndNameIgnoreCase(long workspaceId, String name);

    /** 워크스페이스별 상한(200개) 검사 — 만들 때만 센다 */
    long countByWorkspaceId(long workspaceId);
}
