package net.java21.crowfoot.api.showcase.repository;

import net.java21.crowfoot.api.showcase.domain.SiteShowcase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SiteShowcaseRepository extends JpaRepository<SiteShowcase, Long> {

    String SUMMARY = "select new net.java21.crowfoot.api.showcase.repository.SiteSummary("
            + "s.id, s.modelId, s.url, s.title, s.description, s.siteName, s.faviconUrl, s.thumbnailType, s.capturedAt, "
            + "s.captureError, s.hidden, s.hiddenBy, s.hiddenAt, s.reportCount, s.createdBy, s.createdAt) from SiteShowcase s ";

    Optional<SiteShowcase> findByModelId(Long modelId);

    /** 공개 목록 — 숨기지 않은 사이트, 최근 등록순(Section 3.5) */
    @Query(value = SUMMARY + "where s.hidden = false order by s.createdAt desc, s.id desc",
            countQuery = "select count(s) from SiteShowcase s where s.hidden = false")
    Page<SiteSummary> findVisible(Pageable pageable);

    /** 관리자 목록 — 신고 수 내림차순, 그다음 최근 등록순. hidden이 null이면 전부(Section 3.8) */
    @Query(value = SUMMARY + "where (:hidden is null or s.hidden = :hidden) order by s.reportCount desc, s.createdAt desc, s.id desc",
            countQuery = "select count(s) from SiteShowcase s where (:hidden is null or s.hidden = :hidden)")
    Page<SiteSummary> findForAdmin(@Param("hidden") Boolean hidden, Pageable pageable);

    /** 썸네일 — 숨기지 않은 사이트만(Section 3.6) */
    @Query("select new net.java21.crowfoot.api.showcase.repository.SiteThumbnail(s.thumbnail, s.thumbnailType) "
            + "from SiteShowcase s where s.id = :id and s.hidden = false and s.thumbnail is not null")
    Optional<SiteThumbnail> findVisibleThumbnail(@Param("id") Long id);

    /** 신고 수 원자 증가 — 읽기-수정-쓰기 경합을 막는다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SiteShowcase s set s.reportCount = s.reportCount + 1 where s.id = :id")
    int incrementReportCount(@Param("id") Long id);
}
