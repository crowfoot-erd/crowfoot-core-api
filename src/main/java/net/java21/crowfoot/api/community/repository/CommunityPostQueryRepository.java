package net.java21.crowfoot.api.community.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QUser;
import net.java21.crowfoot.api.community.domain.CommunityBoard;
import net.java21.crowfoot.api.community.domain.QCommunityPost;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 커뮤니티 게시글 조회(Querydsl) — 08-core/08-community.md Section 3.
 * 작성자 LEFT JOIN으로 이름을 함께 내려주고, content_i18n는 목록 프로젝션에서 제외한다(대용량 본문 로드 방지).
 * title_i18n는 해석 전 언어 맵 JSON 문자열 그대로 프로젝션한다(서비스가 lang으로 해석 — §2.1).
 */
@Repository
@RequiredArgsConstructor
public class CommunityPostQueryRepository {

    private final JPAQueryFactory query;

    /** 게시글 행 — content 제외(상세는 엔티티 로드로 별도) */
    public record PostRow(Long id, CommunityBoard board, String titleI18n, Long createdBy, String authorName,
                          Instant createdAt, Instant updatedAt) {
    }

    /** 목록 — 게시판 내 최신순(id desc), keyword는 제목 부분 일치(대소문자 무시) */
    public List<PostRow> search(CommunityBoard board, String keyword, long offset, int limit) {
        QCommunityPost post = QCommunityPost.communityPost;
        QUser author = QUser.user;
        return query
                .select(Projections.constructor(PostRow.class, post.id, post.board, post.titleI18n,
                        post.createdBy, author.name, post.createdAt, post.updatedAt))
                .from(post)
                .leftJoin(author).on(post.createdBy.eq(author.id))
                .where(boardEq(post, board), titleKeyword(post, keyword))
                .orderBy(post.id.desc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    public long count(CommunityBoard board, String keyword) {
        QCommunityPost post = QCommunityPost.communityPost;
        Long count = query.select(post.count())
                .from(post)
                .where(boardEq(post, board), titleKeyword(post, keyword))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 최근글 — 게시판 무관 최신순(대시보드 통합 위젯용) */
    public List<PostRow> recent(int limit) {
        QCommunityPost post = QCommunityPost.communityPost;
        QUser author = QUser.user;
        return query
                .select(Projections.constructor(PostRow.class, post.id, post.board, post.titleI18n,
                        post.createdBy, author.name, post.createdAt, post.updatedAt))
                .from(post)
                .leftJoin(author).on(post.createdBy.eq(author.id))
                .orderBy(post.id.desc())
                .limit(limit)
                .fetch();
    }

    /** 게시판별 최근글 — 릴리스 노트 공개 조회(Section 3.11)가 DB 단계에서 RELEASE_NOTE만 걸러내도록 */
    public List<PostRow> recentByBoard(CommunityBoard board, int limit) {
        QCommunityPost post = QCommunityPost.communityPost;
        QUser author = QUser.user;
        return query
                .select(Projections.constructor(PostRow.class, post.id, post.board, post.titleI18n,
                        post.createdBy, author.name, post.createdAt, post.updatedAt))
                .from(post)
                .leftJoin(author).on(post.createdBy.eq(author.id))
                .where(boardEq(post, board))
                .orderBy(post.id.desc())
                .limit(limit)
                .fetch();
    }

    private static BooleanExpression boardEq(QCommunityPost post, CommunityBoard board) {
        return board == null ? null : post.board.eq(board);
    }

    /** 제목 검색 키워드 — blank면 조건 없음(전체). title_i18n는 4개 언어 값이 함께 담긴 JSON 문자열이라
     *  값 부분 일치가 그대로 성립한다(어느 언어 제목이 걸려도命中 — 시스템 사전 labels 검색과 같은 관례).
     *  JSONB(SqlTypes.JSON) 매핑이라 lower() 등 문자열 함수를 바로 못 쓴다 — HQL cast로 텍스트화(SystemTermQueryRepository 실측 선례). */
    private static BooleanExpression titleKeyword(QCommunityPost post, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        StringExpression titleText = Expressions.stringTemplate("cast({0} as string)", post.titleI18n);
        return titleText.containsIgnoreCase(keyword);
    }
}
