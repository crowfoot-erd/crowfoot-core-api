package net.java21.crowfoot.api.accesstoken.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 워크스페이스 액세스 토큰 (08-core/18-access-token.md Section 2) — MCP 클라이언트가 쓰는 장기 자격.
 * 토큰 하나는 발급한 사용자와 워크스페이스 하나에 묶인다. 원문은 저장하지 않고 SHA-256 해시만 둔다.
 * 폐기한 행은 지우지 않는다 — 감사 기록의 tokenId가 가리키는 대상으로 남는다.
 */
@Entity
@Table(name = "workspace_access_tokens", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long id;

    private Long workspaceId;

    private Long userId;

    private String name;

    private String tokenPrefix;

    @Column(columnDefinition = "CHAR(64)")
    private String tokenHash;

    private Instant expiresAt;

    private Instant lastUsedAt;

    private Instant revokedAt;

    private Instant createdAt;

    public WorkspaceAccessToken(Long workspaceId, Long userId, String name, String tokenPrefix, String tokenHash,
                                Instant expiresAt, Instant createdAt) {
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.name = name;
        this.tokenPrefix = tokenPrefix;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /** 폐기하지 않았고 만료되지 않았다 */
    public boolean isActive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
