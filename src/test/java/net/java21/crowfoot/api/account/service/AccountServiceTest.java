package net.java21.crowfoot.api.account.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.domain.UserIdentity;
import net.java21.crowfoot.api.account.dto.MeResponse;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository;
import net.java21.crowfoot.api.account.repository.UserIdentityQueryRepository;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.client.AuthBlacklistClient;
import net.java21.crowfoot.api.team.repository.TeamQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 계정 API 단위 테스트 (08-core/05-account.md Section 1) —
 * 탈퇴 사전 조건(소유권 정리)과 블랙리스트 등록 → lineage 폐기 → soft 삭제 순서(fail-closed)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserIdentityQueryRepository userIdentityQueryRepository;
    @Mock
    private RefreshTokenQueryRepository refreshTokenQueryRepository;
    @Mock
    private WorkspaceMembershipQueryRepository workspaceMembershipQueryRepository;
    @Mock
    private TeamQueryRepository teamQueryRepository;
    @Mock
    private AuthBlacklistClient authBlacklistClient;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private Clock clock;

    @InjectMocks
    private AccountService accountService;

    @Test
    @DisplayName("내 프로필은 연동 제공자 목록과 admin 여부를 함께 응답한다")
    void meReturnsProfileWithProviders() {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.of(activeUser()));
        given(userIdentityQueryRepository.findByUserId(7L)).willReturn(List.of(
                new UserIdentity(7L, "github", "gh-1", "alice@x.com", "앨리스"),
                new UserIdentity(7L, "kakao", "k-1", "alice@x.com", "앨리스")));

        // when
        MeResponse response = accountService.me(7L);

        // then
        assertThat(response.userId()).isEqualTo("7");
        assertThat(response.providers()).containsExactly("github", "kakao");
        assertThat(response.admin()).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 사용자의 프로필 조회는 404 RESOURCE_NOT_FOUND")
    void meRejectsUnknownUser() {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> accountService.me(7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("내가 Owner인 Workspace에 다른 멤버가 남아 있으면 탈퇴는 409 WITHDRAW_BLOCKED")
    void withdrawBlocksWhenGrantsRemain() {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.of(activeUser()));
        given(workspaceMembershipQueryRepository.countOtherGrantsInOwnerWorkspaces(7L)).willReturn(1L);

        // when & then
        assertThatThrownBy(() -> accountService.withdraw(7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_BLOCKED));
        verify(authBlacklistClient, never()).registerSessionBlacklist(any());
    }

    @Test
    @DisplayName("Owner인 팀이 남아 있어도 탈퇴는 409 WITHDRAW_BLOCKED")
    void withdrawBlocksWhenOwningTeamRemains() {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.of(activeUser()));
        given(workspaceMembershipQueryRepository.countOtherGrantsInOwnerWorkspaces(7L)).willReturn(0L);
        given(teamQueryRepository.existsOwnedTeam(7L)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> accountService.withdraw(7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WITHDRAW_BLOCKED));
    }

    @Test
    @DisplayName("탈퇴는 활성 세션마다 블랙리스트 등록 후 lineage 폐기·withdrawn_at 기록을 수행한다")
    void withdrawRegistersBlacklistThenRevokesAndSoftDeletes() {
        // given
        given(clock.instant()).willReturn(NOW);
        User user = activeUser();
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(workspaceMembershipQueryRepository.countOtherGrantsInOwnerWorkspaces(7L)).willReturn(0L);
        given(teamQueryRepository.existsOwnedTeam(7L)).willReturn(false);
        UUID sid1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sid2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        given(refreshTokenQueryRepository.findActiveSessionIds(7L)).willReturn(List.of(sid1, sid2));

        // when
        accountService.withdraw(7L);

        // then
        InOrder order = inOrder(authBlacklistClient, refreshTokenQueryRepository);
        order.verify(authBlacklistClient).registerSessionBlacklist(sid1.toString());
        order.verify(authBlacklistClient).registerSessionBlacklist(sid2.toString());
        order.verify(refreshTokenQueryRepository).revokeAllByUserId(7L, NOW);
        assertThat(user.getWithdrawnAt()).isEqualTo(NOW);
        verify(auditRecorder).record(eq(7L), eq("USER_WITHDRAWN"), eq("USER"), eq("7"),
                eq(Map.of("revokedSessions", 2)));
    }

    @Test
    @DisplayName("블랙리스트 등록 실패 시 탈퇴는 롤백된다(fail-closed) — lineage 폐기·withdrawn_at 없음")
    void withdrawFailsClosedWhenBlacklistRejected() {
        // given
        given(userRepository.findById(7L)).willReturn(Optional.of(activeUser()));
        given(workspaceMembershipQueryRepository.countOtherGrantsInOwnerWorkspaces(7L)).willReturn(0L);
        given(teamQueryRepository.existsOwnedTeam(7L)).willReturn(false);
        UUID sid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        given(refreshTokenQueryRepository.findActiveSessionIds(7L)).willReturn(List.of(sid));
        willThrow(new RuntimeException("auth unavailable"))
                .given(authBlacklistClient).registerSessionBlacklist(sid.toString());

        // when & then
        assertThatThrownBy(() -> accountService.withdraw(7L)).isInstanceOf(RuntimeException.class);
        verify(refreshTokenQueryRepository, never()).revokeAllByUserId(any(), any());
    }

    private User activeUser() {
        User user = new User("alice@x.com", "앨리스", true);
        user.setId(7L);
        return user;
    }
}
