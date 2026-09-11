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

import java.time.Instant;
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
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMembershipRepository workspaceMembershipRepository;
    @Autowired
    private UserQueryRepository userQueryRepository;

    private long workspaceId;
    private long kimId;

    @BeforeEach
    void seed() {
        kimId = userRepository.save(new User("kim@x.com", "김철수", false)).getId();
        userRepository.save(new User("lee@x.com", "이영희", false)).getId();

        User withdrawn = userRepository.save(new User("old@x.com", "옛날철수", false));
        withdrawn.setWithdrawnAt(Instant.now());

        workspaceId = workspaceRepository.save(new Workspace(
                "워크스페이스", null, kimId, true, kimId)).getId();
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
    @DisplayName("관리자 검색은 탈퇴 사용자도 포함한다(후보 검색과 대비) — userId asc")
    void adminSearchIncludesWithdrawnUsers() {
        // when
        List<AdminUserRow> found = userQueryRepository.searchAdminUsers("철", 0, 10);

        // then — 김철수·옛날철수(탈퇴) 모두 포함
        assertThat(found).hasSize(2);
        assertThat(found).extracting(AdminUserRow::name).containsExactly("김철수", "옛날철수");
        assertThat(found.get(1).withdrawnAt()).isNotNull();
        assertThat(found.get(0).withdrawnAt()).isNull();
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
    @DisplayName("offset·limit을 존중한다")
    void adminSearchRespectsOffsetAndLimit() {
        // when
        List<AdminUserRow> found = userQueryRepository.searchAdminUsers(null, 1, 2);

        // then — id asc 전체 3명 중 2번째부터 2명
        assertThat(found).hasSize(2);
    }
}
