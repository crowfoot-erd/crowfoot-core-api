package net.java21.crowfoot.api.term.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.term.domain.QSystemTerm;
import net.java21.crowfoot.api.term.domain.SystemTerm;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 시스템 사전 조회(Querydsl) (08-core/01-workspace.md Section 4.5) — 페이징·검색 목록.
 * 사용자 목록과 관리 목록이 같은 조회를 공유한다(감사·권한만 서비스가 다르게 얹는다).
 */
@Repository
@RequiredArgsConstructor
public class SystemTermQueryRepository {

    private final JPAQueryFactory query;

    /** 목록 — term 오름차순(사전 순). keyword는 토큰·labels 값 부분 일치, letter는 이니셜(a-z·#) */
    public List<SystemTerm> search(String keyword, String letter, long offset, int limit) {
        QSystemTerm term = QSystemTerm.systemTerm;
        return query.selectFrom(term)
                .where(keywordOf(term, keyword), letterOf(term, letter))
                .orderBy(term.term.asc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    public long count(String keyword, String letter) {
        QSystemTerm term = QSystemTerm.systemTerm;
        Long count = query.select(term.count())
                .from(term)
                .where(keywordOf(term, keyword), letterOf(term, letter))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 검색 키워드 — blank면 조건 없음(전체). labels는 JSON 문자열 컬럼이라 값 부분 일치가 그대로 성립한다 */
    private static BooleanExpression keywordOf(QSystemTerm term, String keyword) {
        return (keyword == null || keyword.isBlank()) ? null
                : term.term.containsIgnoreCase(keyword).or(term.labels.containsIgnoreCase(keyword));
    }

    /** 이니셜 필터 — 소문자 단일 알파벳은 그 글자로 시작(토큰은 정규화돼 항상 소문자),
     *  '#'는 알파벳 외 이니셜(숫자·밑줄 등) */
    private static BooleanExpression letterOf(QSystemTerm term, String letter) {
        if (letter == null || letter.isBlank()) {
            return null;
        }
        if (letter.equals("#")) {
            return term.term.substring(0, 1).lower().notBetween("a", "z");
        }
        return term.term.startsWith(letter);
    }
}
