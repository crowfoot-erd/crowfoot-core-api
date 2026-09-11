package net.java21.crowfoot.api.workspace.domain;

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
 * Workspace 실체 (06-erd/00-domain.md Section 3.8) — 삭제는 멤버십 정리와 같은 트랜잭션 물리 삭제.
 * 소유자는 항상 개인(생성자)이며, 팀 접근은 멤버십 부여(granteeType=TEAM)로만 연결된다.
 * owner_user_id·created_by는 users로의 논리 참조(스키마 그룹 경계 — FK 없음)라 식별자 스칼라로만 둔다.
 */
@Entity
@Table(name = "workspaces", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String description;

    private Long ownerUserId;

    private boolean isDefault;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    public Workspace(String name, String description, Long ownerUserId, boolean isDefault, Long createdBy) {
        this.name = name;
        this.description = description;
        this.ownerUserId = ownerUserId;
        this.isDefault = isDefault;
        this.createdBy = createdBy;
    }
}
