package net.java21.crowfoot.api.community.repository;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.community.domain.CommunityBoard;
import net.java21.crowfoot.api.community.domain.CommunityPost;
import net.java21.crowfoot.api.community.repository.CommunityPostQueryRepository.PostRow;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 커뮤니티 게시글 조회 (08-core/08-community.md Section 3) — 게시판 필터·keyword·페이징·최근글 (H2 PostgreSQL 모드) */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, CommunityPostQueryRepository.class})
class CommunityPostQueryRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CommunityPostRepository communityPostRepository;
    @Autowired
    private CommunityPostQueryRepository communityPostQueryRepository;

    private long marcoId;

    @BeforeEach
    void seed() {
        marcoId = userRepository.save(new User("marco@x.com", "marco", false)).getId();

        communityPostRepository.save(new CommunityPost(CommunityBoard.RELEASE_NOTE, "v1.3.0 릴리스 노트", "# v1.3.0", marcoId));
        communityPostRepository.save(new CommunityPost(CommunityBoard.RELEASE_NOTE, "v1.4.0 릴리스 노트", "# v1.4.0", marcoId));
        communityPostRepository.save(new CommunityPost(CommunityBoard.FEEDBACK, "Bug report: save fails", "본문", marcoId));
        communityPostRepository.save(new CommunityPost(CommunityBoard.FEEDBACK, "검색 필터 개선 제안", "본문", marcoId)); // 가장 나중 삽입 → 최신
    }

    @Test
    @DisplayName("게시판 내 목록 — 최신순(id desc)·작성자 이름 조인·다른 게시판은 제외")
    void searchWithinBoardNewestFirst() {
        List<PostRow> rows = communityPostQueryRepository.search(CommunityBoard.FEEDBACK, null, 0, 20);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).title()).isEqualTo("검색 필터 개선 제안"); // 나중에 삽입 → 최신
        assertThat(rows.get(1).title()).isEqualTo("Bug report: save fails");
        assertThat(rows.get(0).authorName()).isEqualTo("marco");
        assertThat(rows.get(0).board()).isEqualTo(CommunityBoard.FEEDBACK);
    }

    @Test
    @DisplayName("keyword는 제목 부분 일치(대소문자 무시)로 필터한다")
    void searchFiltersByKeywordIgnoreCase() {
        assertThat(communityPostQueryRepository.search(CommunityBoard.FEEDBACK, "bug", 0, 20))
                .extracting(PostRow::title).containsExactly("Bug report: save fails");
        assertThat(communityPostQueryRepository.search(CommunityBoard.FEEDBACK, "제안", 0, 20))
                .extracting(PostRow::title).containsExactly("검색 필터 개선 제안");
        assertThat(communityPostQueryRepository.count(CommunityBoard.FEEDBACK, "BUG")).isEqualTo(1);
        assertThat(communityPostQueryRepository.count(CommunityBoard.FEEDBACK, "없는단어")).isZero();
    }

    @Test
    @DisplayName("offset 페이징 — size 1의 두 번째 페이지는 마지막 행만")
    void searchPagesByOffset() {
        List<PostRow> page2 = communityPostQueryRepository.search(CommunityBoard.RELEASE_NOTE, null, 1, 1);

        assertThat(page2).hasSize(1);
        assertThat(page2.get(0).title()).isEqualTo("v1.3.0 릴리스 노트");
        assertThat(communityPostQueryRepository.count(CommunityBoard.RELEASE_NOTE, null)).isEqualTo(2);
    }

    @Test
    @DisplayName("최근글 — 게시판 무관 최신순으로 limit만큼")
    void recentMergesBoardsNewestFirstWithLimit() {
        List<PostRow> rows = communityPostQueryRepository.recent(3);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).title()).isEqualTo("검색 필터 개선 제안");
        assertThat(rows.get(1).title()).isEqualTo("Bug report: save fails");
        assertThat(rows.get(2).title()).isEqualTo("v1.4.0 릴리스 노트");
        assertThat(rows).allSatisfy(row -> assertThat(row.authorName()).isEqualTo("marco"));
    }
}
