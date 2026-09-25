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

import java.time.Instant;

/**
 * 계정 (06-erd/00-domain.md Section 3.1) — OAuth2 소셜 로그인 전용, 비밀번호 없음.
 * withdrawn_at이 기록되면 탈퇴(soft) — 행은 보존하고 재로그인을 차단한다.
 */
@Entity
@Table(name = "users", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String name;

    private boolean isAdmin;

    /** 계정 단위 UI 언어(ko/en/ja/zh) — NULL이면 미설정(브라우저 감지 따름). 08-core/05-account.md Section 1.4 */
    private String locale;

    private Instant withdrawnAt;

    @CreationTimestamp
    private Instant createdAt;

    public User(String email, String name, boolean isAdmin) {
        this.email = email;
        this.name = name;
        this.isAdmin = isAdmin;
    }

    public boolean isWithdrawn() {
        return withdrawnAt != null;
    }
}
