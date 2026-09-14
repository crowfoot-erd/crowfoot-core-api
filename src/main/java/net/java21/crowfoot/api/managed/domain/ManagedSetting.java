package net.java21.crowfoot.api.managed.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 매니지드 서비스 설정 (08-core/07-managed-database.md Section 2) —
 * 키-값 1행 저장소. v1은 발급 한도(issue_limit) 하나: 관리자가 지정하면
 * 워크스페이스 내 사용자당 기준으로 적용된다. 없으면 기본값(DEFAULT_ISSUE_LIMIT).
 */
@Entity
@Table(name = "managed_settings", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class ManagedSetting {

    /** 발급 한도 키 — 워크스페이스 내 사용자당 최대 발급 수 */
    public static final String KEY_ISSUE_LIMIT = "issue_limit";

    @Id
    @Column(name = "setting_key")
    private String settingKey;

    @Column(name = "setting_value", nullable = false)
    private String settingValue;

    @UpdateTimestamp
    private Instant updatedAt;

    public ManagedSetting(String settingKey, String settingValue) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
    }
}
