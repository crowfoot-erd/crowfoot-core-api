package net.java21.crowfoot.api.account.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 로그인 제공자 코드 (06-erd/00-domain.md Section 3.2.1) — 관리자 화면에서 표시명·활성 토글만 변경.
 */
@Entity
@Table(name = "providers", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class Provider {

    @Id
    private String code;

    private String displayName;

    private boolean isActive;

    public Provider(String code, String displayName, boolean isActive) {
        this.code = code;
        this.displayName = displayName;
        this.isActive = isActive;
    }
}
