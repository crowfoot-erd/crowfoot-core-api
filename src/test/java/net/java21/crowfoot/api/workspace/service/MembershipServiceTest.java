package net.java21.crowfoot.api.workspace.service;

import net.java21.crowfoot.api.account.domain.RoleCode;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserQueryRepository;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.team.repository.TeamMemberQueryRepository;
import net.java21.crowfoot.api.team.repository.TeamRepository;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.Workspace;
import net.java21.crowfoot.api.workspace.domain.WorkspaceMembership;
import net.java21.crowfoot.api.workspace.dto.ChangeRoleRequest;
import net.java21.crowfoot.api.workspace.dto.GrantMembershipRequest;
import net.java21.crowfoot.api.workspace.dto.MembershipIdResponse;
import net.java21.crowfoot.api.workspace.dto.MembershipResponse;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 멤버십 API 단위 테스트 (08-core/03-membership.md) —
 * 부여 규칙(배타·가부·중복·탈퇴 검사)과 마지막 OWNER 보호를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MembershipServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private WorkspaceQueryRepository workspaceQueryRepository;
    @Mock
    private WorkspaceMembershipRepository workspaceMembershipRepository;
    @Mock
    private WorkspaceMembershipQueryRepository workspaceMembershipQueryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserQueryRepository userQueryRepository;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private TeamMemberQueryRepository teamMemberQueryRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private MembershipService membershipService;

    @Test
    @DisplayName("USER 부여는 검사 통과 후 INSERT하고 감사를 남긴다")
    void grantPersistsUserGrantAndAudits() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));
        given(userRepository.findById(7L)).willReturn(Optional.of(activeUser()));
        given(workspaceMembershipQueryRepository.existsGrant(77L, GranteeType.USER, 7L, null))
                .willReturn(false);
        given(workspaceMembershipRepository.save(any(WorkspaceMembership.class)))
                .willAnswer(inv -> {
                    WorkspaceMembership saved = inv.getArgument(0);
                    saved.setId(501L);
                    return saved;
                });

        // when
        MembershipIdResponse response = membershipService.grant(7L, 77L,
                new GrantMembershipRequest("USER", "7", null, "EDITOR"));

        // then
        assertThat(response.membershipId()).isEqualTo("501");
        verify(auditRecorder).record(eq(7L), eq("MEMBERSHIP_GRANTED"), eq("WORKSPACE"), eq("77"), anyMap());
    }

    @Test
    @DisplayName("이미 부여된 대상이면 409 MEMBERSHIP_DUPLICATED — INSERT 없음")
    void grantRejectsDuplicate() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));
        given(userRepository.findById(7L)).willReturn(Optional.of(activeUser()));
        given(workspaceMembershipQueryRepository.existsGrant(77L, GranteeType.USER, 7L, null))
                .willReturn(true);

        // when & then
        assertThatThrownBy(() -> membershipService.grant(7L, 77L,
                new GrantMembershipRequest("USER", "7", null, "EDITOR")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MEMBERSHIP_DUPLICATED));
        verify(workspaceMembershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("탈퇴한 사용자에 대한 부여는 404로 존재를 은닉한다")
    void grantRejectsWithdrawnUser() {
        // given
        User withdrawn = activeUser();
        withdrawn.setWithdrawnAt(Instant.parse("2026-09-01T00:00:00Z"));
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));
        given(userRepository.findById(7L)).willReturn(Optional.of(withdrawn));

        // when & then
        assertThatThrownBy(() -> membershipService.grant(7L, 77L,
                new GrantMembershipRequest("USER", "7", null, "EDITOR")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("granteeType=USER에 teamId를 함께 보내면 400 (배타 위반)")
    void grantRejectsBothUserIdAndTeamId() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));

        // when & then
        assertThatThrownBy(() -> membershipService.grant(7L, 77L,
                new GrantMembershipRequest("USER", "7", "31", "EDITOR")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("OWNER는 부여할 수 없다 — role은 EDITOR/COMMENTER/VIEWER만")
    void grantRejectsOwnerRole() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));

        // when & then
        assertThatThrownBy(() -> membershipService.grant(7L, 77L,
                new GrantMembershipRequest("USER", "7", null, "OWNER")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                            assertThat(ex.getMessage()).isEqualTo("role은 EDITOR, COMMENTER, VIEWER 중 하나여야 합니다");
                        });
    }

    @Test
    @DisplayName("마지막 OWNER 부여는 강등할 수 없다 — 409 LAST_OWNER_PROTECTED")
    void changeRoleProtectsLastOwner() {
        // given
        WorkspaceMembership ownerGrant = membership(RoleCode.OWNER);
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));
        given(workspaceMembershipRepository.findById(501L)).willReturn(Optional.of(ownerGrant));
        given(workspaceMembershipQueryRepository.countOwnerGrants(77L)).willReturn(1L);

        // when & then
        assertThatThrownBy(() -> membershipService.changeRole(7L, 77L, 501L,
                new ChangeRoleRequest("EDITOR")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LAST_OWNER_PROTECTED);
                            assertThat(ex.getMessage()).isEqualTo("마지막 Owner는 강등할 수 없습니다");
                        });
    }

    @Test
    @DisplayName("마지막 OWNER 부여는 회수할 수 없다 — 409 LAST_OWNER_PROTECTED")
    void revokeProtectsLastOwner() {
        // given
        given(workspaceMembershipRepository.findById(501L))
                .willReturn(Optional.of(membership(RoleCode.OWNER)));
        given(workspaceMembershipQueryRepository.countOwnerGrants(77L)).willReturn(1L);

        // when & then
        assertThatThrownBy(() -> membershipService.revoke(7L, 77L, 501L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LAST_OWNER_PROTECTED);
                            assertThat(ex.getMessage()).isEqualTo("마지막 Owner은 제외할 수 없습니다");
                        });
        verify(workspaceMembershipRepository, never()).deleteById(501L);
    }

    @Test
    @DisplayName("역할 변경은 EDITOR→COMMENTER처럼 일반 부여 행에만 동작한다")
    void changeRoleUpdatesAssignableRole() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));
        given(workspaceMembershipRepository.findById(501L))
                .willReturn(Optional.of(membership(RoleCode.EDITOR)));
        given(userRepository.findById(7L)).willReturn(Optional.of(activeUser()));

        // when
        MembershipResponse response = membershipService.changeRole(7L, 77L, 501L,
                new ChangeRoleRequest("COMMENTER"));

        // then
        assertThat(response.role()).isEqualTo("COMMENTER");
        verify(auditRecorder).record(eq(7L), eq("MEMBERSHIP_ROLE_CHANGED"), eq("WORKSPACE"), eq("77"), anyMap());
    }

    @Test
    @DisplayName("후보 검색은 keyword 2자 미만이면 400")
    void candidatesRejectsShortKeyword() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));

        // when & then
        assertThatThrownBy(() -> membershipService.candidates(7L, 77L, "김", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("후보 검색 limit은 기본 10·최대 20으로 클램프한다")
    void candidatesClampsLimit() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));
        given(userQueryRepository.searchWorkspaceCandidates(eq("앨리스"), eq(77L), anyInt()))
                .willReturn(List.of());

        // when
        membershipService.candidates(7L, 77L, "앨리스", 99);

        // then
        verify(userQueryRepository).searchWorkspaceCandidates("앨리스", 77L, 20);
    }

    @Test
    @DisplayName("멤버십이 있는데 유효 역할이 없으면 불변식 위반 IllegalStateException")
    void myWorkspacesFailsFastOnMissingEffectiveRole() {
        // given
        given(workspaceMembershipQueryRepository.findWorkspaceIdsOfUser(7L)).willReturn(List.of(77L));
        given(workspaceQueryRepository.findByIdIn(List.of(77L))).willReturn(List.of(workspace()));
        given(workspaceMembershipQueryRepository.findEffectiveRole(7L, 77L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> membershipService.myWorkspaces(7L))
                .isInstanceOf(IllegalStateException.class);
    }

    private Workspace workspace() {
        Workspace workspace = new Workspace("ERD 작업실", null, 7L, false, 7L);
        workspace.setId(77L);
        return workspace;
    }

    private User activeUser() {
        User user = new User("bob@x.com", "밥", false);
        user.setId(7L);
        return user;
    }

    private WorkspaceMembership membership(RoleCode role) {
        WorkspaceMembership membership = new WorkspaceMembership(
                77L, GranteeType.USER, 7L, null, role, null);
        membership.setId(501L);
        return membership;
    }
}
