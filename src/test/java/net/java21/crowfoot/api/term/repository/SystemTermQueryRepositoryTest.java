package net.java21.crowfoot.api.term.repository;

import net.java21.crowfoot.api.term.domain.SystemTerm;
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

/** 시스템 사전 조회 (08-core/01-workspace.md Section 4.5) — 페이징·keyword·letter (H2 PostgreSQL 모드).
 *  labels는 JSONB 매핑이라 keyword 검색이 문자열 함수 적용 경로를 실제로 지난다
 *  (서비스·웹 테스트는 리포를 모킹해 이 경로를 못 잡는다 — 미검출 회귀 방지). */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, SystemTermQueryRepository.class})
class SystemTermQueryRepositoryTest {

    @Autowired
    private SystemTermRepository systemTermRepository;

    @Autowired
    private SystemTermQueryRepository systemTermQueryRepository;

    @BeforeEach
    void seed() {
        // account·addr(a), email·event(e), user(u), 숫자 이니셜 1st(#) — 이니셜·검색 판별용
        systemTermRepository.save(new SystemTerm("account", "{\"ko\":\"계정\",\"en\":\"Account\"}", null, 2L));
        systemTermRepository.save(new SystemTerm("addr", "{\"ko\":\"주소\"}", null, 2L));
        systemTermRepository.save(new SystemTerm("email", "{\"ko\":\"이메일\",\"en\":\"Email\"}", null, 2L));
        systemTermRepository.save(new SystemTerm("event", "{\"ko\":\"이벤트\"}", null, 2L));
        systemTermRepository.save(new SystemTerm("user", "{\"ko\":\"사용자\",\"en\":\"User\"}", null, 2L));
        systemTermRepository.save(new SystemTerm("1st", "{\"ko\":\"첫째\"}", null, 2L));
    }

    @Test
    @DisplayName("목록 — term 오름차순·페이징(offset/limit)")
    void searchPagedByTermAsc() {
        List<SystemTerm> page1 = systemTermQueryRepository.search(null, null, 0, 2);
        List<SystemTerm> page2 = systemTermQueryRepository.search(null, null, 2, 20);

        assertThat(page1).extracting(SystemTerm::getTerm).containsExactly("1st", "account");
        assertThat(page2).extracting(SystemTerm::getTerm).containsExactly("addr", "email", "event", "user");
    }

    @Test
    @DisplayName("count — 조건 없음 전체")
    void countAll() {
        assertThat(systemTermQueryRepository.count(null, null)).isEqualTo(6);
    }

    @Test
    @DisplayName("keyword — 토큰 부분 일치(대소문자 무시)")
    void searchByTokenKeyword() {
        assertThat(systemTermQueryRepository.search("mail", null, 0, 20))
                .extracting(SystemTerm::getTerm).containsExactly("email");
        assertThat(systemTermQueryRepository.count("MAIL", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("keyword — labels 값 부분 일치(한글·영문 라벨 원문) — JSONB cast 경로")
    void searchByLabelKeyword() {
        assertThat(systemTermQueryRepository.search("이메일", null, 0, 20))
                .extracting(SystemTerm::getTerm).containsExactly("email");
        assertThat(systemTermQueryRepository.search("Account", null, 0, 20))
                .extracting(SystemTerm::getTerm).containsExactly("account");
        assertThat(systemTermQueryRepository.count("사용자", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("letter — 이니셜 필터(e)·'#'(알파벳 외)·미해당 이니셜")
    void searchByLetter() {
        assertThat(systemTermQueryRepository.search(null, "e", 0, 20))
                .extracting(SystemTerm::getTerm).containsExactly("email", "event");
        assertThat(systemTermQueryRepository.search(null, "#", 0, 20))
                .extracting(SystemTerm::getTerm).containsExactly("1st");
        assertThat(systemTermQueryRepository.search(null, "z", 0, 20)).isEmpty();
    }

    @Test
    @DisplayName("keyword·letter 조합 — 이니셜 안에서 라벨 검색")
    void searchByKeywordAndLetter() {
        assertThat(systemTermQueryRepository.search("이벤트", "e", 0, 20))
                .extracting(SystemTerm::getTerm).containsExactly("event");
        assertThat(systemTermQueryRepository.count("계정", "z")).isZero();
    }
}
