package net.java21.crowfoot.api.team.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserQueryRepository;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.team.domain.Team;
import net.java21.crowfoot.api.team.domain.TeamMember;
import net.java21.crowfoot.api.team.dto.AddTeamMemberRequest;
import net.java21.crowfoot.api.team.dto.CreateTeamRequest;
import net.java21.crowfoot.api.team.dto.CreateTeamResponse;
import net.java21.crowfoot.api.team.repository.TeamMemberQueryRepository;
import net.java21.crowfoot.api.team.repository.TeamMemberRepository;
import net.java21.crowfoot.api.team.repository.TeamQueryRepository;
import net.java21.crowfoot.api.team.repository.TeamRepository;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 팀 API 단위 테스트 (08-core/04-team.md) —
 * 생성 2-INSERT(팀·Owner 멤버 — Workspace는 만들지 않음), 해체 순서(팀 부여→멤버→팀),
 * 멤버 관리 규칙을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock
    private TeamRepository teamRepository;
    @Mock
    private TeamQueryRepository teamQueryRepository;
    @Mock
    private TeamMemberRepository teamMemberRepository;
    @Mock
    private TeamMemberQueryRepository teamMemberQueryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserQueryRepository userQueryRepository;
    @Mock
    private WorkspaceMembershipRepository workspaceMembershipRepository;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private TeamService teamService;

    @Test
    @DisplayName("생성은 팀·Owner 멤버만 넣는다 — Workspace·팀 부여 멤버십은 만들지 않는다")
    void createPersistsTeamWithOwnerMemberOnly() {
        // given
        given(teamRepository.save(any(Team.class))).willAnswer(inv -> {
            Team saved = inv.getArgument(0);
            saved.setId(31L);
            return saved;
        });

        // when
        CreateTeamResponse response =
                teamService.create(7L, new CreateTeamRequest("플랫폼팀", "설명"));

        // then
        assertThat(response.teamId()).isEqualTo("31");
        verify(workspaceMembershipRepository, never()).save(any());
        InOrder order = inOrder(teamRepository, teamMemberRepository);
        order.verify(teamRepository).save(any(Team.class));
        order.verify(teamMemberRepository).save(any(TeamMember.class));
    }

    @Test
    @DisplayName("비소속 팀 조회는 존재 은닉으로 404 TEAM_NOT_FOUND")
    void getRejectsNonMemberAs404() {
        // given
        given(teamMemberRepository.existsByTeamIdAndUserId(31L, 7L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> teamService.get(7L, 31L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TEAM_NOT_FOUND));
    }

    @Test
    @DisplayName("소속이어도 Owner가 아니면 정보 변경은 403")
    void patchRejectsNonOwner() {
        // given
        given(teamMemberRepository.existsByTeamIdAndUserId(31L, 7L)).willReturn(true);
        given(teamRepository.findById(31L)).willReturn(Optional.of(team(9L)));

        // when & then
        assertThatThrownBy(() -> teamService.patch(7L, 31L,
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED);
                            assertThat(ex.getMessage()).isEqualTo("팀 정보 변경 권한이 없습니다");
                        });
    }

    @Test
    @DisplayName("멤버 추가는 활성 사용자만 — 중복이면 409 TEAM_MEMBER_DUPLICATED")
    void addMemberRejectsDuplicate() {
        // given
        given(teamMemberRepository.existsByTeamIdAndUserId(31L, 7L)).willReturn(true);
        given(teamRepository.findById(31L)).willReturn(Optional.of(team(7L)));
        given(userRepository.findById(8L)).willReturn(Optional.of(activeUser(8L)));
        given(teamMemberRepository.existsByTeamIdAndUserId(31L, 8L)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> teamService.addMember(7L, 31L, new AddTeamMemberRequest("8")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TEAM_MEMBER_DUPLICATED));
        verify(teamMemberRepository, never()).save(any(TeamMember.class));
    }

    @Test
    @DisplayName("멤버 추가 성공은 저장 후 userId를 돌려준다 (201 header-only)")
    void addMemberPersistsAndReturnsUserId() {
        // given
        given(teamMemberRepository.existsByTeamIdAndUserId(31L, 7L)).willReturn(true);
        given(teamRepository.findById(31L)).willReturn(Optional.of(team(7L)));
        given(userRepository.findById(8L)).willReturn(Optional.of(activeUser(8L)));
        given(teamMemberRepository.existsByTeamIdAndUserId(31L, 8L)).willReturn(false);

        // when
        Long added = teamService.addMember(7L, 31L, new AddTeamMemberRequest("8"));

        // then
        assertThat(added).isEqualTo(8L);
        verify(teamMemberRepository).save(any(TeamMember.class));
    }

    @Test
    @DisplayName("팀 Owner 본인은 해체 외에 제외할 수 없다 — 400")
    void removeMemberRejectsOwner() {
        // given
        given(teamMemberRepository.existsByTeamIdAndUserId(31L, 7L)).willReturn(true);
        given(teamRepository.findById(31L)).willReturn(Optional.of(team(7L)));

        // when & then
        assertThatThrownBy(() -> teamService.removeMember(7L, 31L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                            assertThat(ex.getMessageKey()).isEqualTo("detail.team.owner.protected");
                        });
    }

    @Test
    @DisplayName("해체는 팀 부여 멤버십 → 멤버 → 팀 순서로 물리 삭제한다")
    void dissolveDeletesGrantsMembersThenTeam() {
        // given
        given(teamMemberRepository.existsByTeamIdAndUserId(31L, 7L)).willReturn(true);
        given(teamRepository.findById(31L)).willReturn(Optional.of(team(7L)));

        // when
        teamService.dissolve(7L, 31L);

        // then
        InOrder order = inOrder(workspaceMembershipRepository, teamMemberRepository, teamRepository);
        order.verify(workspaceMembershipRepository).deleteByGranteeTypeAndTeamId(GranteeType.TEAM, 31L);
        order.verify(teamMemberRepository).deleteByTeamId(31L);
        order.verify(teamRepository).deleteById(31L);
    }

    @Test
    @DisplayName("팀 멤버 후보 검색도 keyword 2자 미만이면 400")
    void memberCandidatesRejectsShortKeyword() {
        // given
        given(teamMemberRepository.existsByTeamIdAndUserId(31L, 7L)).willReturn(true);
        given(teamRepository.findById(31L)).willReturn(Optional.of(team(7L)));

        // when & then
        assertThatThrownBy(() -> teamService.memberCandidates(7L, 31L, "김", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private Team team(long ownerUserId) {
        Team team = new Team("플랫폼팀", null, ownerUserId);
        team.setId(31L);
        return team;
    }

    private User activeUser(long id) {
        User user = new User("carol@x.com", "캐롤", false);
        user.setId(id);
        return user;
    }
}
