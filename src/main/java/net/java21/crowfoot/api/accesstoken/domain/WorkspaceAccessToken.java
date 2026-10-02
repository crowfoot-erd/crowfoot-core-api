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
 * 토큰 하나는 발급한 사용자와 워크스페이스 하나에 묶인다. 검증은 SHA-256 해시로 한다.
 * 원문은 암호화해서 함께 둔다 — 발급한 본인이 등록 명령을 다시 복사할 수 있게 한다(커넥션 비밀번호와 같은 암호화).
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

    /** 암호화한 원문(AES-256-GCM, ConnectionCrypto) — 발급한 본인에게만 되돌려 준다. 이 컬럼이 생기기 전의 토큰은 null이다 */
    private byte[] tokenEncrypted;

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
