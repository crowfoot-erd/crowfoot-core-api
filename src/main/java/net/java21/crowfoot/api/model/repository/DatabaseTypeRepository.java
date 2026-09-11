package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.DatabaseType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** ERD 문서 데이터베이스 종류 코드 접근 (JPA). */
public interface DatabaseTypeRepository extends JpaRepository<DatabaseType, String> {

    List<DatabaseType> findByIsActiveTrueOrderByCodeAsc();

    /** 관리자 전체 조회(활성 포함) — code asc */
    List<DatabaseType> findAllByOrderByCodeAsc();

    Optional<DatabaseType> findByCodeAndIsActiveTrue(String code);
}
