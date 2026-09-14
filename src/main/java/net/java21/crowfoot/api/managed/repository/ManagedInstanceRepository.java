package net.java21.crowfoot.api.managed.repository;

import net.java21.crowfoot.api.managed.domain.ManagedInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 매니지드 루트 인스턴스 — 관리자 등록 순(생성순) 목록 */
public interface ManagedInstanceRepository extends JpaRepository<ManagedInstance, Long> {

    List<ManagedInstance> findAllByOrderByCreatedAtAscIdAsc();

    List<ManagedInstance> findByIsActiveTrueOrderByCreatedAtAscIdAsc();
}
