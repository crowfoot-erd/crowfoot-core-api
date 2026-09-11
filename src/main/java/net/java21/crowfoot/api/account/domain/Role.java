package net.java21.crowfoot.api.account.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 워크스페이스 역할 코드 (06-erd/00-domain.md Section 3.5.1) — level은 권한 서열(클수록 강력).
 * 코드·level 변경은 배포 수반, 표시명 수정만 관리자 화면 제공.
 */
@Entity
@Table(name = "roles", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    private String code;

    private String displayName;

    private int level;

    public Role(String code, String displayName, int level) {
        this.code = code;
        this.displayName = displayName;
        this.level = level;
    }
}
