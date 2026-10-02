package net.java21.crowfoot.api.accesstoken.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.java21.crowfoot.api.accesstoken.domain.WorkspaceAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceAccessTokenRepository extends JpaRepository<WorkspaceAccessToken, Long> {

    /** 워크스페이스의 유효한 토큰 — 폐기·만료를 뺀다. 발급 일시 내림차순 */
    @Query("select t from WorkspaceAccessToken t where t.workspaceId = :workspaceId and t.revokedAt is null"
            + " and (t.expiresAt is null or t.expiresAt > :now) order by t.createdAt desc, t.id desc")
    List<WorkspaceAccessToken> findActiveByWorkspaceId(@Param("workspaceId") long workspaceId, @Param("now") Instant now);

    /** 워크스페이스 안에서 사용자 한 명의 유효한 토큰 수 — 5개 상한 판정 */
    @Query("select count(t) from WorkspaceAccessToken t where t.workspaceId = :workspaceId and t.userId = :userId"
            + " and t.revokedAt is null and (t.expiresAt is null or t.expiresAt > :now)")
    long countActive(@Param("workspaceId") long workspaceId, @Param("userId") long userId, @Param("now") Instant now);

    Optional<WorkspaceAccessToken> findByIdAndWorkspaceId(long id, long workspaceId);

    Optional<WorkspaceAccessToken> findByTokenHash(String tokenHash);

    /** 회원 탈퇴 — 그 사용자의 토큰을 전부 폐기한다 */
    @Modifying(clearAutomatically = true)
    @Query("update WorkspaceAccessToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllByUserId(@Param("userId") long userId, @Param("now") Instant now);

    /** 마지막 사용 시각 — 1분에 한 번만 쓴다(조건이 맞는 행만 갱신) */
    @Modifying(clearAutomatically = true)
    @Query("update WorkspaceAccessToken t set t.lastUsedAt = :now where t.id = :id"
            + " and (t.lastUsedAt is null or t.lastUsedAt < :threshold)")
    int touchLastUsed(@Param("id") long id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
