package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

/** 단건 CRUD 전용 — list·search 조회는 UserQueryRepository(Querydsl)를 사용한다. */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Admin Bootstrap 판정 — Admin 부재 시 최초 GitHub 로그인 자동 부여 */
    long countByIsAdminTrue();

    /** 탈퇴하지 않은 관리자 id — 제안 및 신고 새 글 알림의 수신자(08-core/11-notification.md Section 2.2) */
    @org.springframework.data.jpa.repository.Query("select u.id from User u where u.isAdmin = true and u.withdrawnAt is null")
    java.util.List<Long> findActiveAdminIds();
}
