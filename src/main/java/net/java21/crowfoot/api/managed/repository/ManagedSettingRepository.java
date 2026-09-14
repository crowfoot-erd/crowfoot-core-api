package net.java21.crowfoot.api.managed.repository;

import net.java21.crowfoot.api.managed.domain.ManagedSetting;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 매니지드 서비스 설정 — @Id가 setting_key 문자열이라 findById로 바로 조회한다.
 */
public interface ManagedSettingRepository extends JpaRepository<ManagedSetting, String> {
}
