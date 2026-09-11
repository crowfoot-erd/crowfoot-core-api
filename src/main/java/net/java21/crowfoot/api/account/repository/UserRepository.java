package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

/** 단건 CRUD 전용 — list·search 조회는 UserQueryRepository(Querydsl)를 사용한다. */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Admin Bootstrap 판정 — Admin 부재 시 최초 GitHub 로그인 자동 부여 */
    long countByIsAdminTrue();
}
