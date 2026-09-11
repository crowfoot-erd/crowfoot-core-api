package net.java21.crowfoot.api.workspace.repository;

import net.java21.crowfoot.api.workspace.domain.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

/** 단건 CRUD 전용 — 목록·존재 검사는 WorkspaceQueryRepository(Querydsl)를 사용한다. */
public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {
}
