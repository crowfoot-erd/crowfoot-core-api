package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.Model;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** ERD 문서 쓰기 접근 (JPA). */
public interface ModelRepository extends JpaRepository<Model, Long> {

    /** Workspace 내 이름 중복 검사 — UNIQUE(workspace_id, name) 앱 레벨 선검사 (1.2 409) */
    boolean existsByWorkspaceIdAndName(Long workspaceId, String name);

    /** 메타 변경(1.4) — 자기 자신 제외 이름 중복 검사 */
    boolean existsByWorkspaceIdAndNameAndIdNot(Long workspaceId, String name, Long id);

    Optional<Model> findByIdAndWorkspaceId(Long id, Long workspaceId);
}
