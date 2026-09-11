package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
