package net.java21.crowfoot.api.metrics.repository;

import net.java21.crowfoot.api.metrics.domain.VisitEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 방문 이벤트 원본 접근 (JPA) — 직전 이벤트 조회(30초 중복·그날 첫 방문 판정)와 보존 정리.
 */
public interface VisitEventRepository extends JpaRepository<VisitEvent, Long> {

    /** 방문자 쿠키 UUID의 직전 이벤트 — 30초 중복·그날 첫 방문 판정 공용 */
    Optional<VisitEvent> findFirstByVisitorUuidOrderByOccurredAtDescIdDesc(UUID visitorUuid);

    /** 쿠키를 받지 못한 방문자의 일일 IP 해시 직전 이벤트 — 같은 판정 */
    Optional<VisitEvent> findFirstByVisitorIpHashOrderByOccurredAtDescIdDesc(String visitorIpHash);

    /** 보존 정리 배치 — dateKst가 cutoff 미만인 행 삭제(08-core/10-metrics.md Section 5.3) */
    @Modifying
    @Query("delete from VisitEvent v where v.dateKst < :cutoff")
    long deleteByDateKstBefore(@Param("cutoff") LocalDate cutoff);
}
