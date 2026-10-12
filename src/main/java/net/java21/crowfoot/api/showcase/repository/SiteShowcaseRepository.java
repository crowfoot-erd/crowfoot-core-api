package net.java21.crowfoot.api.showcase.repository;

import net.java21.crowfoot.api.showcase.domain.SiteShowcase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SiteShowcaseRepository extends JpaRepository<SiteShowcase, Long> {

    String SUMMARY = "select new net.java21.crowfoot.api.showcase.repository.SiteSummary("
            + "s.id, s.modelId, s.url, s.title, s.description, s.siteName, s.faviconUrl, s.thumbnailType, s.capturedAt, "
            + "s.captureError, s.hidden, s.hiddenBy, s.hiddenAt, s.reportCount, s.createdBy, s.createdAt) from SiteShowcase s ";

    Optional<SiteShowcase> findByModelId(Long modelId);

    boolean existsByModelId(Long modelId);

    /** 사이트가 등록된 문서 — 문서 목록 한 쪽의 id를 한 번에 본다(IN 배치, v1.42) */
    @Query("select s.modelId from SiteShowcase s where s.modelId in :modelIds")
    List<Long> findModelIdsIn(@Param("modelIds") Collection<Long> modelIds);

    /**
     * 정기 다시 찍기 후보(v1.42) — 숨기지 않았고, 찍은 지 오래됐거나 아직 못 찍었고, 마지막 시도가 retryBefore 이전인 사이트.
     * 아직 못 찍은 사이트, 오래된 사이트 순이다
     */
    @Query("select s.id from SiteShowcase s where s.hidden = false "
            + "and (s.capturedAt is null or s.capturedAt < :staleBefore) "
            + "and (s.captureAttemptedAt is null or s.captureAttemptedAt < :retryBefore) "
            + "order by case when s.capturedAt is null then 0 else 1 end, s.capturedAt asc, s.id asc")
    List<Long> findRefreshCandidates(@Param("staleBefore") Instant staleBefore, @Param("retryBefore") Instant retryBefore,
                                     Pageable pageable);

    /**
     * 다시 찍기 선점 — 마지막 시도 시각을 원자적으로 바꾼다. 1이면 이 인스턴스가 맡는다(core 레플리카 둘이 같은 사이트를
     * 함께 찍지 않게, v1.42). 사용자의 다시 가져오기와도 같은 시각을 본다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SiteShowcase s set s.captureAttemptedAt = :now where s.id = :id "
            + "and (s.captureAttemptedAt is null or s.captureAttemptedAt < :retryBefore)")
    int claimCapture(@Param("id") Long id, @Param("now") Instant now, @Param("retryBefore") Instant retryBefore);

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
