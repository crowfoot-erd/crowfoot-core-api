package net.java21.crowfoot.api.account.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 감사·폐기 이력 (06-erd/00-domain.md Section 3.7) — INSERT-only, 갱신·삭제 없음.
 */
@Entity
@Table(name = "audit_logs", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 행위 주체 — NULL은 시스템(만료 정리 배치 등) */
    private Long actorUserId;

    private String action;

    private String targetType;

    /** 대상 식별자(BIGINT·UUID 혼재 → 문자열) */
    private String targetId;

    /** JSON 문자열 — JSONB 컬럼 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    private String ip;

    @CreationTimestamp
    private Instant createdAt;

    public AuditLog(Long actorUserId, String action, String targetType, String targetId, String detail, String ip) {
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.ip = ip;
    }
}
