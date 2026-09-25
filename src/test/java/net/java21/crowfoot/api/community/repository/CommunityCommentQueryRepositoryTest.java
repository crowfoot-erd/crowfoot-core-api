package net.java21.crowfoot.api.community.repository;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.community.domain.CommunityBoard;
import net.java21.crowfoot.api.community.domain.CommunityComment;
import net.java21.crowfoot.api.community.domain.CommunityPost;
import net.java21.crowfoot.api.community.repository.CommunityCommentQueryRepository.CommentRow;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 커뮤니티 코멘트 조회 (08-core/08-community.md Section 3) — 게시글별 오래된 순·grouped count (H2 PostgreSQL 모드) */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, CommunityCommentQueryRepository.class})
class CommunityCommentQueryRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CommunityPostRepository communityPostRepository;
    @Autowired
    private CommunityCommentRepository communityCommentRepository;
    @Autowired
    private CommunityCommentQueryRepository communityCommentQueryRepository;

    private long feedbackPostId;
    private long otherPostId;

    @BeforeEach
    void seed() {
        long marcoId = userRepository.save(new User("marco@x.com", "marco", false)).getId();
        long poloId = userRepository.save(new User("polo@x.com", "polo", false)).getId();

        feedbackPostId = communityPostRepository
                .save(new CommunityPost(CommunityBoard.FEEDBACK, Map.of("ko", "제안"), Map.of("ko", "본문"), marcoId)).getId();
        otherPostId = communityPostRepository
                .save(new CommunityPost(CommunityBoard.FEEDBACK, Map.of("ko", "다른 제안"), Map.of("ko", "본문"), poloId)).getId();

        communityCommentRepository.save(new CommunityComment(feedbackPostId, "첫 댓글", marcoId));
        communityCommentRepository.save(new CommunityComment(feedbackPostId, "둘째 댓글", poloId));
        communityCommentRepository.save(new CommunityComment(otherPostId, "다른 게시글 댓글", poloId));
    }

    @Test
    @DisplayName("게시글 코멘트 목록 — 오래된 순(asc)·작성자 이름 조인·타 게시글 제외")
    void findByPostIdJoinsAuthorAscending() {
        List<CommentRow> rows = communityCommentQueryRepository.findByPostId(feedbackPostId);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(CommentRow::content).containsExactly("첫 댓글", "둘째 댓글");
        assertThat(rows.get(0).authorName()).isEqualTo("marco");
        assertThat(rows.get(1).authorName()).isEqualTo("polo");
        assertThat(rows).allSatisfy(row -> assertThat(row.postId()).isEqualTo(feedbackPostId));
    }

    @Test
    @DisplayName("post-id 집합별 건수 — grouped count 1회, 없는 게시글은 맵에 없다")
    void countByPostIdsGroupsCorrectly() {
        Map<Long, Long> counts = communityCommentQueryRepository.countByPostIds(List.of(feedbackPostId, otherPostId, 999L));

        assertThat(counts).containsEntry(feedbackPostId, 2L).containsEntry(otherPostId, 1L).hasSize(2);
        assertThat(counts).doesNotContainKey(999L);
    }

    @Test
    @DisplayName("빈 집합 조회 — 쿼리 없이 빈 맵")
    void countByPostIdsEmptyReturnsEmptyMap() {
        assertThat(communityCommentQueryRepository.countByPostIds(List.of())).isEmpty();
        assertThat(communityCommentQueryRepository.countByPostIds(null)).isEmpty();
    }
}
