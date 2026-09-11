package net.java21.crowfoot.api.workspace.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.java21.crowfoot.api.account.domain.RoleCode;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 멤버십 (06-erd/00-domain.md Section 3.5) — 개인·팀 부여 × 역할.
 * workspace_id는 workspaces로의 논리 참조(그룹 경계 — FK 없음), 나머지 사용자·팀·역할은 물리 FK지만
 * 앱 코드는 전부 식별자 스칼라로 다룬다.
 */
@Entity
@Table(name = "workspace_memberships", schema = "crowfoot_core",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_workspace_memberships_workspace_user", columnNames = {"workspace_id", "user_id"}),
                @UniqueConstraint(name = "uq_workspace_memberships_workspace_team", columnNames = {"workspace_id", "team_id"})
        })
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;

    @Enumerated(EnumType.STRING)
    private GranteeType granteeType;

    private Long userId;

    private Long teamId;

    @Enumerated(EnumType.STRING)
    private RoleCode role;

    private Long grantedBy;

    @CreationTimestamp
    private Instant grantedAt;

    public WorkspaceMembership(Long workspaceId, GranteeType granteeType, Long userId, Long teamId,
                               RoleCode role, Long grantedBy) {
        this.workspaceId = workspaceId;
        this.granteeType = granteeType;
        this.userId = userId;
        this.teamId = teamId;
        this.role = role;
        this.grantedBy = grantedBy;
    }
}
