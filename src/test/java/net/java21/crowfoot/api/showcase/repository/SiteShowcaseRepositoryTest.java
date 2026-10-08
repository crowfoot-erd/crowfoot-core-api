package net.java21.crowfoot.api.showcase.repository;

import net.java21.crowfoot.api.showcase.domain.SiteShowcase;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사이트 쇼케이스 쿼리 검증 (H2 PostgreSQL 모드, 08-core/19-site-showcase.md Section 3.5·3.6·3.8) — JPQL 생성자 프로젝션이
 * 썸네일 없이 실행되는지, 공개 목록이 숨긴 사이트를 빼고 최근 등록순인지, 관리자 목록의 숨김 필터와 신고 수 정렬,
 * 썸네일 조회와 신고 수 원자 증가를 본다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslTestConfig.class)
class SiteShowcaseRepositoryTest {

    @Autowired
    private SiteShowcaseRepository repository;

    private SiteShowcase site(long modelId, String title, boolean hidden, int reports, byte[] thumbnail) {
        SiteShowcase site = new SiteShowcase(modelId, "https://" + title + ".example.com", title, 2L);
        site.setHidden(hidden);
        site.setReportCount(reports);
        if (thumbnail != null) {
            site.setThumbnail(thumbnail);
            site.setThumbnailType("image/jpeg");
            site.setCapturedAt(Instant.parse("2026-10-08T07:40:00Z"));
        }
        return repository.saveAndFlush(site);
    }

    @Test
    @DisplayName("공개 목록은 숨긴 사이트를 빼고 최근 등록순, 관리자 목록은 숨김 필터와 신고 수 내림차순")
    void lists() {
        SiteShowcase first = site(1L, "first", false, 0, null);
        SiteShowcase hidden = site(2L, "hidden", true, 3, null);
        SiteShowcase reported = site(3L, "reported", false, 2, new byte[] {1, 2});

        Page<SiteSummary> visible = repository.findVisible(PageRequest.of(0, 10));
        assertThat(visible.getTotalElements()).isEqualTo(2);
        assertThat(visible.getContent()).extracting(SiteSummary::title).containsExactly("reported", "first");
        assertThat(visible.getContent().get(0).thumbnailType()).isEqualTo("image/jpeg");
        assertThat(visible.getContent().get(1).thumbnailType()).isNull();

        assertThat(repository.findForAdmin(null, PageRequest.of(0, 10)).getContent())
                .extracting(SiteSummary::title).containsExactly("hidden", "reported", "first");
        assertThat(repository.findForAdmin(true, PageRequest.of(0, 10)).getContent())
                .extracting(SiteSummary::id).containsExactly(hidden.getId());
        assertThat(repository.findForAdmin(false, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(first.getId()).isNotNull();
        assertThat(reported.getId()).isNotNull();
    }

    @Test
    @DisplayName("썸네일은 숨기지 않았고 그림이 있을 때만, 신고 수는 원자적으로 1 오른다")
    void thumbnailAndReports() {
        SiteShowcase withImage = site(1L, "image", false, 0, new byte[] {(byte) 0xFF, (byte) 0xD8});
        SiteShowcase noImage = site(2L, "none", false, 0, null);
        SiteShowcase hiddenImage = site(3L, "hidden", true, 0, new byte[] {1});

        assertThat(repository.findVisibleThumbnail(withImage.getId())).hasValueSatisfying(thumbnail -> {
            assertThat(thumbnail.image()).containsExactly((byte) 0xFF, (byte) 0xD8);
            assertThat(thumbnail.type()).isEqualTo("image/jpeg");
        });
        assertThat(repository.findVisibleThumbnail(noImage.getId())).isEmpty();
        assertThat(repository.findVisibleThumbnail(hiddenImage.getId())).isEmpty();

        assertThat(repository.incrementReportCount(withImage.getId())).isEqualTo(1);
        assertThat(repository.findById(withImage.getId()).orElseThrow().getReportCount()).isEqualTo(1);
    }
}
