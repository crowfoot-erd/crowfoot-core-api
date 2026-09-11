package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProviderRepository extends JpaRepository<Provider, String> {

    List<Provider> findByIsActiveTrueOrderByCodeAsc();

    /** 관리자 코드 테이블 — 활성 여부 무관 전체 */
    List<Provider> findAllByOrderByCodeAsc();
}
