package net.java21.crowfoot.api.team.domain;

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
 * 팀 (06-erd/00-domain.md Section 3.3) — 해체는 물리 삭제, 사전 조건 "팀 소유 Workspace 없음"은 앱이 검사.
 * owner_user_id는 생성자 고정(이전은 후속).
 */
@Entity
@Table(name = "teams", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String description;

    private Long ownerUserId;

    @CreationTimestamp
    private Instant createdAt;

    public Team(String name, String description, Long ownerUserId) {
        this.name = name;
        this.description = description;
        this.ownerUserId = ownerUserId;
    }
}
