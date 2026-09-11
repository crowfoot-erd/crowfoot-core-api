package net.java21.crowfoot.api.account.domain;

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
 * OAuth2 제공자 연동 (06-erd/00-domain.md Section 3.2) — 1 사용자 N 제공자.
 * (provider, provider_user_id) UNIQUE가 최초 로그인 upsert 판정 키다.
 * FK(users·providers)는 논리 참조 대신 물리 FK지만, 앱 코드는 식별자 스칼라로만 다룬다.
 */
@Entity
@Table(name = "user_identities", schema = "crowfoot_core",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_user_identities_provider_uid", columnNames = {"provider", "provider_user_id"}),
                @UniqueConstraint(name = "uq_user_identities_user_provider", columnNames = {"user_id", "provider"})
        })
@Getter
@Setter
@NoArgsConstructor
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String provider;

    private String providerUserId;

    private String email;

    private String name;

    @CreationTimestamp
    private Instant linkedAt;

    public UserIdentity(Long userId, String provider, String providerUserId, String email, String name) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
        this.name = name;
    }
}
