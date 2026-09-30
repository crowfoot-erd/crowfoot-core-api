package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.RoleCode;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserQueryRepository.AdminUserRow;
import net.java21.crowfoot.api.account.repository.UserQueryRepository.UserCandidateResponse;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.Workspace;
import net.java21.crowfoot.api.workspace.domain.WorkspaceMembership;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceRepository;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 후보 검색 — 이름·이메일 부분 일치, 탈퇴·이미 부여된 사용자 제외 (H2 PostgreSQL 모드) */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, UserQueryRepository.class})
class UserQueryRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMembershipRepository workspaceMembershipRepository;
    @Autowired
    private UserQueryRepository userQueryRepository;

    private long workspaceId;
    private long kimId;

    /** createdAt 고정 기준 — 가입순: 김철수 → 이영희 → 옛날철수 */
    private static final Instant BASE = Instant.parse("2026-09-30T00:00:00Z");

    @BeforeEach
    void seed() {
        kimId = persistWithCreatedAt("kim@x.com", "김철수", BASE.minus(3, ChronoUnit.HOURS)).getId();
        persistWithCreatedAt("lee@x.com", "이영희", BASE.minus(2, ChronoUnit.HOURS));

        User withdrawn = persistWithCreatedAt("old@x.com", "옛날철수", BASE.minus(1, ChronoUnit.HOURS));
        withdrawn.setWithdrawnAt(Instant.now());

        workspaceId = workspaceRepository.save(new Workspace(
                "워크스페이스", null, kimId, true, kimId)).getId();
    }

    /** @CreationTimestamp는 ORM에서 불변이라 네이티브 UPDATE로 값을 고정한다(정렬 단언의 결정성 — 동시각 tiebreaker 검증 포함) */
    private User persistWithCreatedAt(String email, String name, Instant createdAt) {
        User user = userRepository.save(new User(email, name, false));
        jdbcTemplate.update("update crowfoot_core.users set created_at = ? where id = ?",
                Timestamp.from(createdAt), user.getId());
        return user;
    }

    @Test
    @DisplayName("이름 부분 일치 검색 — 2자 이하 키워드로도 쿼리 위임은 그대로")
    void searchByName() {
        // when
        List<UserCandidateResponse> found = userQueryRepository.searchWorkspaceCandidates("철", workspaceId, 10);

        // then
        assertThat(found).hasSize(1);
        assertThat(found.get(0).userId()).isEqualTo(Long.toString(kimId));
        assertThat(found.get(0).name()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("이메일 부분 일치 검색")
    void searchByEmail() {
        // when
        List<UserCandidateResponse> found = userQueryRepository.searchWorkspaceCandidates("lee@", workspaceId, 10);

        // then
        assertThat(found).hasSize(1);
        assertThat(found.get(0).email()).isEqualTo("lee@x.com");
    }

    @Test
    @DisplayName("이미 개인 부여된 사용자와 탈퇴 사용자는 후보에서 제외된다")
    void excludesGrantedAndWithdrawn() {
        // given
        workspaceMembershipRepository.save(new WorkspaceMembership(
                workspaceId, GranteeType.USER, kimId, null, RoleCode.VIEWER, kimId));

        // when
        List<UserCandidateResponse> found = userQueryRepository.searchWorkspaceCandidates("철", workspaceId, 10);

        // then
        assertThat(found).isEmpty(); // 김철수(부여됨)·옛날철수(탈퇴) 모두 제외
    }

    @Test
    @DisplayName("limit을 존중한다")
    void respectsLimit() {
        // when
        List<UserCandidateResponse> found = userQueryRepository.searchWorkspaceCandidates("x.com", workspaceId, 1);

        // then
        assertThat(found).hasSize(1);
    }

    // ----- 관리자 — 전체 사용자 목록 (08-core/05-account.md Section 2.1) -----

    @Test
    @DisplayName("관리자 검색은 탈퇴 사용자도 포함한다(후보 검색과 대비) — createdAt desc")
    void adminSearchIncludesWithdrawnUsers() {
        // when
        List<AdminUserRow> found = userQueryRepository.searchAdminUsers("철", 0, 10);

        // then — 김철수·옛날철수(탈퇴) 모두 포함(가입이 늦은 옛날철수가 먼저)
        assertThat(found).hasSize(2);
        assertThat(found).extracting(AdminUserRow::name).containsExactly("옛날철수", "김철수");
        assertThat(found.get(0).withdrawnAt()).isNotNull();
        assertThat(found.get(1).withdrawnAt()).isNull();
    }

    @Test
    @DisplayName("keyword가 없으면 전체를 조회한다")
    void adminSearchWithoutKeywordReturnsAll() {
        // when
        List<AdminUserRow> found = userQueryRepository.searchAdminUsers(null, 0, 10);

        // then
        assertThat(found).hasSize(3); // 김철수·이영희·옛날철수
        assertThat(userQueryRepository.countAdminUsers(null)).isEqualTo(3);
    }

    @Test
    @DisplayName("관리자 목록은 createdAt desc(최신 가입순)로 정렬한다")
    void adminSearchOrdersByCreatedAtDesc() {
        // when
        List<AdminUserRow> found = userQueryRepository.searchAdminUsers(null, 0, 10);

        // then — 가입순 역순
        assertThat(found).extracting(AdminUserRow::name)
                .containsExactly("옛날철수", "이영희", "김철수");
    }

    @Test
    @DisplayName("같은 createdAt면 userId desc로 순서를 확정한다(페이징 안정성)")
    void adminSearchTiebreaksByUserIdDesc() {
        // given — 같은 시각(최신)에 가입한 2명
        persistWithCreatedAt("new-a@x.com", "신규A", BASE);
        persistWithCreatedAt("new-b@x.com", "신규B", BASE);

        // when
        List<AdminUserRow> found = userQueryRepository.searchAdminUsers(null, 0, 10);

        // then — 나중에 저장된(id가 큰) 신규B가 먼저
        assertThat(found.get(0).name()).isEqualTo("신규B");
        assertThat(found.get(1).name()).isEqualTo("신규A");
        assertThat(found.get(2).name()).isEqualTo("옛날철수");
    }

    @Test
    @DisplayName("offset·limit을 존중한다")
    void adminSearchRespectsOffsetAndLimit() {
        // when
        List<AdminUserRow> found = userQueryRepository.searchAdminUsers(null, 1, 2);

        // then — createdAt desc [옛날철수, 이영희, 김철수] 중 2번째부터 2명
        assertThat(found).extracting(AdminUserRow::name).containsExactly("이영희", "김철수");
    }
}
