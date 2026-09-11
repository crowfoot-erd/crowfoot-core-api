package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {

    Optional<Role> findByCode(String code);

    /** 관리자 코드 테이블 — 서열 내림차순(08-core/05-account.md Section 2.6) */
    List<Role> findAllByOrderByLevelDesc();
}
