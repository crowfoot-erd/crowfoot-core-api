package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 일별 로그인 집계(관리자 트래픽 활동 탭 — 08-core/10-metrics.md Section 7).
     * 하루 경계는 KST — created_at을 Asia/Seoul로 환산해 날짜를 자른다.
     * 행 배열: [date(YYYY-MM-DD), action, count]
     */
    @Query(value = """
            SELECT to_char((a.created_at AT TIME ZONE 'Asia/Seoul')::date, 'YYYY-MM-DD') AS day,
                   a.action, COUNT(*)
            FROM crowfoot_core.audit_logs a
            WHERE a.created_at >= :from AND a.action IN ('LOGIN_SUCCEEDED', 'LOGIN_FAILED')
            GROUP BY 1, 2
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> countDailyLogins(@Param("from") Instant from);

    /** 액션별 총수(기능 사용량 — 내림차순). 행 배열: [action, count] */
    @Query(value = """
            SELECT a.action, COUNT(*) AS c
            FROM crowfoot_core.audit_logs a
            WHERE a.created_at >= :from
            GROUP BY a.action
            ORDER BY c DESC
            """, nativeQuery = true)
    List<Object[]> countByAction(@Param("from") Instant from);
}
