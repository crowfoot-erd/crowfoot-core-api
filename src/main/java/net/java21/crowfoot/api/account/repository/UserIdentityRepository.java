package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 단건 CRUD 전용 — 사용자별 연동 목록은 UserIdentityQueryRepository(Querydsl)를 사용한다. */
public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    /** 최초 로그인 upsert 판정 키 — (provider, provider_user_id) UNIQUE */
    Optional<UserIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);
}
