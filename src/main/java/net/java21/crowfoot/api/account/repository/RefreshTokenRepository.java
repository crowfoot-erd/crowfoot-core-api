package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 단건 CRUD 전용 — lineage 폐기·활성 세션 조회는 RefreshTokenQueryRepository(Querydsl)를 사용한다. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByJti(UUID jti);
}
