package net.java21.crowfoot.api.account.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh 저장소 (06-erd/00-domain.md Section 3.6) — 발급마다 신규 행, Rotation마다 구 jti 폐기.
 * sid(세션)가 lineage 키 — 관리자 세션 조회·전량 폐기의 단위다.
 */
@Entity
@Table(name = "refresh_tokens", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    private UUID jti;

    private UUID sessionId;

    private Long userId;

    private String tokenHash;

    private Instant issuedAt;

    private Instant lastUsedAt;

    private Instant expiresAt;

    private Instant rotatedAt;

    private Instant revokedAt;

    private String ip;

    private String userAgent;

    public RefreshToken(UUID jti, UUID sessionId, Long userId, String tokenHash,
                        Instant issuedAt, Instant lastUsedAt, Instant expiresAt, String ip, String userAgent) {
        this.jti = jti;
        this.sessionId = sessionId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.lastUsedAt = lastUsedAt;
        this.expiresAt = expiresAt;
        this.ip = ip;
        this.userAgent = userAgent;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
