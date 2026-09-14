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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 매니지드 DB 루트 인스턴스 (08-core/07-managed-database.md Section 2) —
 * 관리자가 등록한 root 커넥션. 발급 스키마 프로비저닝(CREATE/DROP SCHEMA)과
 * 발급 커넥션의 자격 원천이 된다. created_by는 논리 참조(users).
 */
@Entity
@Table(name = "managed_instances", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class ManagedInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "instance_id")
    private Long id;

    private String displayName;

    /** 프로비저닝 전략 키(postgresql·mysql) — ManagedProvisioner 선택에 쓰인다 */
    private String dbmsType;

    private String host;

    private int port;

    /** 접속 database — PostgreSQL은 필수, MySQL은 발급 시 database를 새로 만들어 NULL */
    private String databaseName;

    private String username;

    /** AES-256-GCM 암호문(iv ‖ ciphertext‖tag) — ConnectionCrypto로만 해석된다 */
    private byte[] password;

    private boolean isActive;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public ManagedInstance(String displayName, String dbmsType, String host, int port,
                           String databaseName, String username, byte[] password,
                           boolean isActive, Long createdBy) {
        this.displayName = displayName;
        this.dbmsType = dbmsType;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
        this.isActive = isActive;
        this.createdBy = createdBy;
    }
}
