package net.java21.crowfoot.api.connection.domain;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * DB 커넥션 (08-core/06-connection.md Section 2) — 워크스페이스 단위 접속 정보.
 * 리버스 엔지니어링(스키마 → 문서)·포워드 배포가 이 자격으로 대상 DB에 접속한다.
 * dbms_type·created_by는 논리 참조(스키마 그룹 경계 — FK는 workspace_id만).
 */
@Entity
@Table(name = "db_connections", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class DbConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "connection_id")
    private Long id;

    private Long workspaceId;

    private String name;

    /** DBMS 종류 — database_types 코드 논리 참조. introspector·dialect 전략 선택 키 */
    private String dbmsType;

    private String host;

    private int port;

    private String databaseName;

    /** PostgreSQL 스키마 — 지정하면 introspection·배포가 이 스키마를 대상으로 한다. null이면 기본 스키마 */
    private String schemaName;

    private String username;

    /** AES-256-GCM 암호문(iv ‖ ciphertext‖tag) — ConnectionCrypto로만 해석된다 */
    private byte[] password;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public DbConnection(Long workspaceId, String name, String dbmsType, String host, int port,
                        String databaseName, String schemaName, String username, byte[] password, Long createdBy) {
        this.workspaceId = workspaceId;
        this.name = name;
        this.dbmsType = dbmsType;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.username = username;
        this.password = password;
        this.createdBy = createdBy;
    }
}
