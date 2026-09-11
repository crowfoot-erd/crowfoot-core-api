package net.java21.crowfoot.api.workspace.repository;

import net.java21.crowfoot.api.account.domain.Role;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.RoleRepository;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.team.domain.Team;
import net.java21.crowfoot.api.team.domain.TeamMember;
import net.java21.crowfoot.api.team.repository.TeamMemberRepository;
import net.java21.crowfoot.api.team.repository.TeamRepository;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.Workspace;
import net.java21.crowfoot.api.workspace.domain.WorkspaceMembership;
import net.java21.crowfoot.api.account.domain.RoleCode;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유효 역할·멤버 집계 쿼리 검증 (H2 PostgreSQL 모드).
 * 개인 부여 ∪ 소속 팀 부여의 max level 판정이 인가(RoleChecker)의 기반이다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, WorkspaceMembershipQueryRepository.class})
class WorkspaceMembershipQueryRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TeamMemberRepository teamMemberRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMembershipRepository workspaceMembershipRepository;
    @Autowired
    private WorkspaceMembershipQueryRepository membershipQueryRepository;

    private long alice;
    private long bob;
    private long carol;
    private long workspaceId;
    private long teamId;

    @BeforeEach
    void seed() {
        roleRepository.save(new Role("OWNER", "소유자", 40));
        roleRepository.save(new Role("EDITOR", "편집자", 30));
        roleRepository.save(new Role("COMMENTER", "댓글 작성자", 20));
        roleRepository.save(new Role("VIEWER", "뷰어", 10));

        alice = userRepository.save(new User("alice@x.com", "앨리스", false)).getId();
        bob = userRepository.save(new User("bob@x.com", "밥", false)).getId();
        carol = userRepository.save(new User("carol@x.com", "캐럴", false)).getId();

        workspaceId = workspaceRepository.save(new Workspace(
                "팀 스페이스", null, alice, false, alice)).getId();
        teamId = teamRepository.save(new Team("웹서비스팀", null, alice)).getId();
    }

    @Test
    @DisplayName("개인 부여만 있으면 그 역할이 유효 역할이다")
    void directGrantOnly() {
        // given
        grant(workspaceId, GranteeType.USER, alice, null, RoleCode.EDITOR);

        // when
        Optional<EffectiveRole> role = membershipQueryRepository.findEffectiveRole(alice, workspaceId);

        // then
        assertThat(role).hasValueSatisfying(it -> {
            assertThat(it.code()).isEqualTo("EDITOR");
            assertThat(it.level()).isEqualTo(30);
        });
    }

    @Test
    @DisplayName("소속 팀 부여도 유효 역할에 합산된다")
    void teamGrantCountsForMember() {
        // given
        grant(workspaceId, GranteeType.TEAM, null, teamId, RoleCode.VIEWER);
        teamMemberRepository.save(new TeamMember(teamId, alice, null));

        // when
        Optional<EffectiveRole> role = membershipQueryRepository.findEffectiveRole(alice, workspaceId);

        // then
        assertThat(role).hasValueSatisfying(it -> {
            assertThat(it.code()).isEqualTo("VIEWER");
            assertThat(it.level()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("개인과 팀 부여가 겹치면 높은 역할(level)이 이긴다")
    void maxLevelWins() {
        // given
        grant(workspaceId, GranteeType.USER, alice, null, RoleCode.COMMENTER); // 20
        grant(workspaceId, GranteeType.TEAM, null, teamId, RoleCode.EDITOR);  // 30
        teamMemberRepository.save(new TeamMember(teamId, alice, null));

        // when
        Optional<EffectiveRole> role = membershipQueryRepository.findEffectiveRole(alice, workspaceId);

        // then
        assertThat(role).hasValueSatisfying(it -> {
            assertThat(it.code()).isEqualTo("EDITOR");
            assertThat(it.level()).isEqualTo(30);
        });
    }

    @Test
    @DisplayName("부여가 없으면 멤버가 아니다 (empty)")
    void noGrantMeansNotMember() {
        // when & then
        assertThat(membershipQueryRepository.findEffectiveRole(carol, workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("팀 미소속이면 그 팀 부여는 무효다")
    void teamGrantRequiresTeamMembership() {
        // given
        grant(workspaceId, GranteeType.TEAM, null, teamId, RoleCode.EDITOR);
        // alice는 팀에 소속되지 않음 (team_members 행 없음)

        // when & then
        assertThat(membershipQueryRepository.findEffectiveRole(alice, workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("유효 멤버 수는 개인 부여와 팀 부여 멤버의 합집합 인원 수다")
    void countDistinctMembersIsUnion() {
        // given
        // 개인: alice, bob / 팀: bob(겹침), carol → distinct = {alice, bob, carol} = 3
        grant(workspaceId, GranteeType.USER, alice, null, RoleCode.EDITOR);
        grant(workspaceId, GranteeType.USER, bob, null, RoleCode.VIEWER);
        grant(workspaceId, GranteeType.TEAM, null, teamId, RoleCode.COMMENTER);
        teamMemberRepository.save(new TeamMember(teamId, bob, null));
        teamMemberRepository.save(new TeamMember(teamId, carol, null));

        // when & then
        assertThat(membershipQueryRepository.countDistinctMembers(workspaceId)).isEqualTo(3);
    }

    @Test
    @DisplayName("탈퇴 사전조건 — 본인 Owner 워크스페이스의 본인 외 부여만 카운트된다")
    void otherGrantsInOwnerWorkspaces() {
        // given
        grant(workspaceId, GranteeType.USER, alice, null, RoleCode.OWNER);
        grant(workspaceId, GranteeType.USER, bob, null, RoleCode.EDITOR);

        // when & then
        assertThat(membershipQueryRepository.countOtherGrantsInOwnerWorkspaces(alice)).isEqualTo(1);
        assertThat(membershipQueryRepository.countOtherGrantsInOwnerWorkspaces(bob)).isZero();
    }

    @Test
    @DisplayName("동일 피부여자 부여 존재 검사")
    void existsGrantByGrantee() {
        // given
        grant(workspaceId, GranteeType.USER, alice, null, RoleCode.EDITOR);

        // when & then
        assertThat(membershipQueryRepository.existsGrant(workspaceId, GranteeType.USER, alice, null)).isTrue();
        assertThat(membershipQueryRepository.existsGrant(workspaceId, GranteeType.USER, bob, null)).isFalse();
        assertThat(membershipQueryRepository.existsGrant(workspaceId, GranteeType.TEAM, null, teamId)).isFalse();
    }

    private void grant(long wsId, GranteeType type, Long userId, Long granteeTeamId, RoleCode role) {
        workspaceMembershipRepository.save(new WorkspaceMembership(
                wsId, type, userId, granteeTeamId, role, alice));
    }
}
