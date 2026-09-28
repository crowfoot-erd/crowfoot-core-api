package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.repository.ShareQueryRepository.SitemapRow;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사이트맵 원료 쿼리 검증 (H2 PostgreSQL 모드, 08-core/02-model.md Section 1.10.10).
 * 프로젝션 레코드를 실제로 실행한다 — 서비스 단위 테스트는 저장소를 모킹해
 * Projections.constructor 대상 생성자 접근(클래스 레벨 public 필요)까지는 못 본다
 * (ModelFeedbackQueryRepositoryTest와 같은 취지).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, ShareQueryRepository.class})
class ShareQueryRepositoryTest {

    private static final Instant PAST = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    @Autowired
    private ModelRepository modelRepository;
    @Autowired
    private ModelShareRepository shareRepository;
    @Autowired
    private ShareQueryRepository shareQueryRepository;

    @Test
    @DisplayName("활성 링크의 문서당 최신 토큰만, 문서 갱신순(updatedAt desc)으로 — 종료·예약·옛 링크 제외")
    void sitemapRowsActiveLatestPerModelNewestFirst() {
        // given — m1(주문)에 활성 링크 2개(옛→최신), m2(회원)에 1개, 비활성 2종, m1의 가장 마지막 링크는 종료된 링크
        Model order = modelRepository.save(new Model(77L, "주문 ERD", null, "postgresql", "{}", 2L));
        Model member = modelRepository.save(new Model(77L, "회원 ERD", null, "mysql", "{}", 2L));
        Model closed = modelRepository.save(new Model(77L, "종료된 ERD", null, "mysql", "{}", 2L));
        Model reserved = modelRepository.save(new Model(77L, "예약된 ERD", null, "mysql", "{}", 2L));

        shareRepository.save(new ModelShare(order.getId(), "tokA-old", null, null, 2L));
        shareRepository.save(new ModelShare(order.getId(), "tokA", null, null, 2L));
        // m1의 가장 나중 발급이 종료 링크 — 활성 최신 판정은 활성 링크끼리만(비활성이 최신 링크를 가리지 않는다)
        shareRepository.save(new ModelShare(order.getId(), "tokA-dead", PAST, PAST, 2L));
        shareRepository.save(new ModelShare(member.getId(), "tokB", null, null, 2L));
        shareRepository.save(new ModelShare(closed.getId(), "tokC", PAST, PAST, 2L));
        shareRepository.save(new ModelShare(reserved.getId(), "tokD", FUTURE, null, 2L));

        // 회원 ERD를 나중에 갱신 — 갱신순 정렬의 원료(updatedAt이 쿼리의 lastmod이기도 하다)
        member.setDescription("갱신");
        modelRepository.saveAndFlush(member);

        // when
        List<SitemapRow> rows = shareQueryRepository.findSitemapShares(Instant.now(), 100);

        // then — 문서 2건(활성), 문서당 최신 활성 토큰, 최신 갱신 문서가 먼저
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).shareToken()).isEqualTo("tokB");
        assertThat(rows.get(0).lastmod()).isAfter(rows.get(1).lastmod());
        assertThat(rows.get(1).shareToken()).isEqualTo("tokA"); // 옛 링크·종료 링크가 아니다
        assertThat(rows).extracting(SitemapRow::shareToken)
                .doesNotContain("tokA-old", "tokA-dead", "tokC", "tokD");
    }

    @Test
    @DisplayName("limit 상한 — 최신 갱신 문서 순으로 자른다")
    void sitemapRowsRespectLimit() {
        Model m1 = modelRepository.save(new Model(77L, "ERD 1", null, "postgresql", "{}", 2L));
        Model m2 = modelRepository.save(new Model(77L, "ERD 2", null, "postgresql", "{}", 2L));
        Model m3 = modelRepository.save(new Model(77L, "ERD 3", null, "postgresql", "{}", 2L));
        shareRepository.save(new ModelShare(m1.getId(), "tok1", null, null, 2L));
        shareRepository.save(new ModelShare(m2.getId(), "tok2", null, null, 2L));
        shareRepository.save(new ModelShare(m3.getId(), "tok3", null, null, 2L));

        List<SitemapRow> rows = shareQueryRepository.findSitemapShares(Instant.now(), 2);

        // 삽입(=갱신) 순서의 역순 — 최신 2건만
        assertThat(rows).extracting(SitemapRow::shareToken).containsExactly("tok3", "tok2");
    }

    @Test
    @DisplayName("활성 링크가 없으면 빈 목록이다")
    void sitemapRowsEmptyWithoutActiveShare() {
        Model m = modelRepository.save(new Model(77L, "종료 ERD", null, "mysql", "{}", 2L));
        shareRepository.save(new ModelShare(m.getId(), "tokC", PAST, PAST, 2L));

        assertThat(shareQueryRepository.findSitemapShares(Instant.now(), 100)).isEmpty();
    }
}
