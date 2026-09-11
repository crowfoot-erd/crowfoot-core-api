package net.java21.crowfoot.api.team.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 팀 소속 (06-erd/00-domain.md Section 3.4) — 역할 컬럼 없음(Owner 판정은 teams.owner_user_id).
 * 최초 멤버(Owner) 행은 초대가 아니라 생성이므로 added_by = NULL.
 */
@Entity
@Table(name = "team_members", schema = "crowfoot_core",
        uniqueConstraints = @UniqueConstraint(name = "uq_team_members_team_user", columnNames = {"team_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long teamId;

    private Long userId;

    @CreationTimestamp
    private Instant joinedAt;

    /** 추가한 주체(감사 보조) — 최초 멤버(Owner)는 NULL */
    private Long addedBy;

    public TeamMember(Long teamId, Long userId, Long addedBy) {
        this.teamId = teamId;
        this.userId = userId;
        this.addedBy = addedBy;
    }
}
