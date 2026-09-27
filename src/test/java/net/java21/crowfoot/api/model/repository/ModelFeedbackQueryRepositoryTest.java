package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelComment;
import net.java21.crowfoot.api.model.domain.ModelReaction;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.repository.ModelCommentQueryRepository.MyCommentRow;
import net.java21.crowfoot.api.model.repository.ModelReactionQueryRepository.MyReactionRow;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.BeforeEach;
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
 * 내 피드백 역조회 쿼리 검증 (H2 PostgreSQL 모드, 08-core/02-model.md Section 1.10.9).
 * 프로젝션 레코드를 실제로 실행한다 — 서비스 단위 테스트는 쿼리 리포지토리를 모킹해
 * Projections.constructor의 대상 생성자 접근(클래스 레벨 public 필요)까지는 못 본다.
 * 2026-09-28 운영 500(로컬 레코드 프로젝션 실패 — 내 댓글·좋아요 문서 전멸)의 회귀 게이트.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, ModelCommentQueryRepository.class, ModelReactionQueryRepository.class})
class ModelFeedbackQueryRepositoryTest {

    @Autowired
    private ModelRepository modelRepository;
    @Autowired
    private ModelCommentRepository commentRepository;
    @Autowired
    private ModelReactionRepository reactionRepository;
    @Autowired
    private ModelShareRepository shareRepository;
    @Autowired
    private ModelCommentQueryRepository commentQueryRepository;
    @Autowired
    private ModelReactionQueryRepository reactionQueryRepository;

    private static final long ME = 2L;
    private static final long OTHER = 3L;

    private long modelA; // 링크 2건(대표=최근 발급)
    private long modelB; // 링크 없음 — 토큰 null·viewCount 0

    @BeforeEach
    void seed() {
        Model a = modelRepository.save(new Model(101L, "주문 ERD", "설명", "postgresql", "{}", 2L));
        Model b = modelRepository.save(new Model(101L, "정산 ERD", null, "mysql", "{}", 2L));
        modelA = a.getId();
        modelB = b.getId();

        // 링크 — A에 2건(오래된 것 먼저), B에는 없음
        shareRepository.save(new ModelShare(modelA, "tok-old", Instant.now().minusSeconds(3600), null, 2L));
        ModelShare representative = shareRepository.save(
                new ModelShare(modelA, "tok-new", Instant.now().minusSeconds(60), null, 2L));
        representative.setViewCount(42L);

        // 댓글 — 내 회원 원댓글(A)·내 오너 답글(A)·타인 댓글(A·제외)·비회원 댓글(B·제외)·내 댓글(B)
        Long mine = commentRepository.save(new ModelComment(modelA, null, "내 원댓글", ME)).getId();
        commentRepository.save(new ModelComment(modelA, mine, "오너 답글", ME));
        commentRepository.save(new ModelComment(modelA, null, "타인 댓글", OTHER));
        commentRepository.save(new ModelComment(modelB, "방문자", "비회원 댓글", "hash"));
        commentRepository.save(new ModelComment(modelB, null, "링크 없는 문서의 내 댓글", ME));

        // 카운터(문서 단위) — B에만 눈에 띄는 값
        a.setReactionCount(7L);
        a.setCommentCount(3L);
        b.setReactionCount(1L);
        b.setCommentCount(2L);

        // 반응 — ME가 A·B에, 타인은 A에(제외 대상)
        ModelReaction onA = new ModelReaction();
        onA.setModelId(modelA);
        onA.setUserId(ME);
        reactionRepository.save(onA);
        ModelReaction onB = new ModelReaction();
        onB.setModelId(modelB);
        onB.setUserId(ME);
        reactionRepository.save(onB);
        ModelReaction others = new ModelReaction();
        others.setModelId(modelA);
        others.setUserId(OTHER);
        reactionRepository.save(others);
    }

    @Test
    @DisplayName("내 댓글 — authorUserId 일치 행만 id desc, 대표 링크는 최근 발급 토큰(없으면 null)")
    void findMyComments() {
        List<MyCommentRow> rows = commentQueryRepository.findByAuthorUserIdOrderByIdDesc(ME);

        assertThat(rows).hasSize(3); // 타인·비회원 댓글은 제외
        // 최신 활동순 — 링크 없는 문서(B) 댓글이 마지막에 삽입돼 첫 행
        assertThat(rows.get(0).modelName()).isEqualTo("정산 ERD");
        assertThat(rows.get(0).shareToken()).isNull(); // 링크 철회 후에도 댓글은 산다(1.10.9)
        assertThat(rows.get(1).content()).isEqualTo("오너 답글");
        assertThat(rows.get(1).parentCommentId()).isNotNull();
        assertThat(rows.get(2).content()).isEqualTo("내 원댓글");
        assertThat(rows.get(2).shareToken()).isEqualTo("tok-new"); // 2건 중 최근 발급이 대표
        assertThat(rows).allSatisfy(row -> assertThat(row.databaseType()).isNotBlank());
    }

    @Test
    @DisplayName("내 반응 — userId 일치 문서만 최근 반응순, 카운터·대표 링크 조인(없으면 토큰 null·조회 0)")
    void findMyReactions() {
        List<MyReactionRow> rows = reactionQueryRepository.findByUserIdOrderByIdDesc(ME);

        assertThat(rows).hasSize(2); // 타인 반응은 제외
        assertThat(rows.get(0).modelName()).isEqualTo("정산 ERD"); // 최근 반응순(id desc)
        assertThat(rows.get(0).shareToken()).isNull();
        assertThat(rows.get(0).viewCount()).isZero();
        assertThat(rows.get(0).reactionCount()).isEqualTo(1L);
        assertThat(rows.get(0).commentCount()).isEqualTo(2L);
        assertThat(rows.get(1).modelName()).isEqualTo("주문 ERD");
        assertThat(rows.get(1).shareToken()).isEqualTo("tok-new");
        assertThat(rows.get(1).viewCount()).isEqualTo(42L); // 대표 링크의 조회 수
    }
}
