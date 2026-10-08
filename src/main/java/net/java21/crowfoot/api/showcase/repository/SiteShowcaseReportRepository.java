package net.java21.crowfoot.api.showcase.repository;

import net.java21.crowfoot.api.showcase.domain.SiteShowcaseReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SiteShowcaseReportRepository extends JpaRepository<SiteShowcaseReport, Long> {

    boolean existsByShowcaseIdAndUserId(Long showcaseId, Long userId);

    /** 다시 보이게 하거나 지울 때 신고 기록을 함께 지운다(Section 3.4·3.9) */
    @Modifying
    @Query("delete from SiteShowcaseReport r where r.showcaseId = :showcaseId")
    void deleteByShowcaseId(@Param("showcaseId") Long showcaseId);
}
