package net.java21.crowfoot.api.managed.repository;

import net.java21.crowfoot.api.managed.domain.ManagedDatabase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 매니지드 발급 이력 — 목록(워크스페이스)·한도 집계(인스턴스×사용자)·
 * 커넥션 보호 조회(발급 커넥션 수정·삭제 차단)·철회 대상 조회.
 */
public interface ManagedDatabaseRepository extends JpaRepository<ManagedDatabase, Long> {

    List<ManagedDatabase> findByWorkspaceIdOrderByCreatedAtAscIdAsc(long workspaceId);

    /** 워크스페이스 내 사용자 발급 수 — 한도 기준(인스턴스 구분 없이, 워크스페이스 안에서 합산) */
    long countByWorkspaceIdAndUserId(long workspaceId, long userId);

    /** 관리자 목록의 issuedCount — 인스턴스 전체 발급 수 */
    long countByInstanceId(long instanceId);

    /** 스키마명 규칙 충돌 검사 — UNIQUE 제약 앞 단계 진단 */
    Optional<ManagedDatabase> findByInstanceIdAndSchemaName(long instanceId, String schemaName);

    /** 발급 커넥션 보호 — 커넥션 API(06 Section 3.3~3.4)의 수정·삭제 차단 판정 */
    Optional<ManagedDatabase> findByConnectionId(long connectionId);

    /** 철회 대상 — 워크스페이스 소속이 아니면 404 은닉 */
    Optional<ManagedDatabase> findByIdAndWorkspaceId(long databaseId, long workspaceId);
}
