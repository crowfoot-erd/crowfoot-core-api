package net.java21.crowfoot.api.managed.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 매니지드 발급 이력 (08-core/07-managed-database.md Section 2) — 1행 = 1 발급.
 * 스키마명 규칙 cf_u{userId}_d{seq}, 자동 생성 커넥션(connection_id)과 생사를 같이한다.
 * user_id·workspace_id·connection_id는 논리 참조, instance_id만 물리 FK.
 */
@Entity
@Table(name = "managed_databases", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class ManagedDatabase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "database_id")
    private Long id;

    private Long instanceId;

    /** 소유자 — 철회는 본인만 */
    private Long userId;

    /** 발급 시점 워크스페이스 — 커넥션이 등록된 곳 */
    private Long workspaceId;

    /** 발급 스키마 — cf_u{userId}_d{seq}, (instance_id, schema_name) 유일 */
    private String schemaName;

    /** 발급 계정 — 스키마 전용 DB 사용자(계정명 = 스키마명). 구방식(스키마-only) 이력은 null */
    private String username;

    /** 발급 계정 비밀번호 암호문 — AES-256-GCM(ConnectionCrypto). 접속 정보 조회 시 복호화 */
    private byte[] password;

    /** 자동 생성 커넥션 — db_connections 논리 참조. 철회가 함께 삭제한다 */
    private Long connectionId;

    @CreationTimestamp
    private Instant createdAt;

    public ManagedDatabase(Long instanceId, Long userId, Long workspaceId, String schemaName, Long connectionId) {
        this.instanceId = instanceId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.schemaName = schemaName;
        this.connectionId = connectionId;
    }

    public ManagedDatabase(Long instanceId, Long userId, Long workspaceId, String schemaName,
                           String username, byte[] password, Long connectionId) {
        this(instanceId, userId, workspaceId, schemaName, connectionId);
        this.username = username;
        this.password = password;
    }
}
