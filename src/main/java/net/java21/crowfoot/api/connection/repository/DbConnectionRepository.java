package net.java21.crowfoot.api.connection.repository;

import net.java21.crowfoot.api.connection.domain.DbConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 커넥션 리포지토리 — 워크스페이스 하위 자원이라 소속 조건이 항상 붙는다 (08-core/06-connection.md Section 2) */
public interface DbConnectionRepository extends JpaRepository<DbConnection, Long> {

    List<DbConnection> findByWorkspaceIdOrderByCreatedAtAscIdAsc(long workspaceId);

    Optional<DbConnection> findByIdAndWorkspaceId(long id, long workspaceId);
}
