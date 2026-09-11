package net.java21.crowfoot.api.internal.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.domain.UserIdentity;
import net.java21.crowfoot.api.account.domain.WorkspaceConstants;
import net.java21.crowfoot.api.account.domain.RoleCode;
import net.java21.crowfoot.api.account.repository.UserIdentityRepository;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.internal.dto.GetOrCreateUserRequest;
import net.java21.crowfoot.api.internal.dto.GetOrCreateUserResponse;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.Workspace;
import net.java21.crowfoot.api.workspace.domain.WorkspaceMembership;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 사용자 확보(get-or-create) 내부 API 단위 테스트 (08-core/05-account.md Section 3.1) —
 * 최초 로그인 프로비저닝 4-INSERT와 Admin Bootstrap 규칙을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class InternalAccountServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserIdentityRepository userIdentityRepository;
    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private WorkspaceMembershipRepository workspaceMembershipRepository;

    @InjectMocks
    private InternalAccountService internalAccountService;

    private final GetOrCreateUserRequest githubRequest =
            new GetOrCreateUserRequest("github", "gh-1", "alice@x.com", "앨리스");

    @Test
    @DisplayName("기존 연동이면 created=false로 사용자를 돌려준다 — INSERT 없음")
    void getOrCreateReturnsExistingUser() {
        // given
        given(userIdentityRepository.findByProviderAndProviderUserId("github", "gh-1"))
                .willReturn(Optional.of(new UserIdentity(7L, "github", "gh-1", "alice@x.com", "앨리스")));
        given(userRepository.findById(7L)).willReturn(Optional.of(user(true)));

        // when
        GetOrCreateUserResponse response = internalAccountService.getOrCreate(githubRequest);

        // then
        assertThat(response.userId()).isEqualTo("7");
        assertThat(response.created()).isFalse();
        assertThat(response.admin()).isTrue();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("탈퇴한 계정의 재로그인은 409 USER_WITHDRAWN")
    void getOrCreateRejectsWithdrawnUser() {
        // given
        User withdrawn = user(false);
        withdrawn.setWithdrawnAt(Instant.parse("2026-09-01T00:00:00Z"));
        given(userIdentityRepository.findByProviderAndProviderUserId("github", "gh-1"))
                .willReturn(Optional.of(new UserIdentity(7L, "github", "gh-1", "alice@x.com", "앨리스")));
        given(userRepository.findById(7L)).willReturn(Optional.of(withdrawn));

        // when & then
        assertThatThrownBy(() -> internalAccountService.getOrCreate(githubRequest))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_WITHDRAWN));
    }

    @Test
    @DisplayName("최초 GitHub 로그인은 4-INSERT 프로비저닝 후 created=true — Admin 없으면 admin 자동 부여(Bootstrap)")
    void getOrCreateProvisionsOnFirstLoginWithAdminBootstrap() {
        // given
        given(userIdentityRepository.findByProviderAndProviderUserId("github", "gh-1"))
                .willReturn(Optional.empty());
        given(userRepository.countByIsAdminTrue()).willReturn(0L);
        given(userRepository.save(any(User.class))).willAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        given(workspaceRepository.save(any(Workspace.class))).willAnswer(inv -> {
            Workspace saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        // when
        GetOrCreateUserResponse response = internalAccountService.getOrCreate(githubRequest);

        // then
        assertThat(response.userId()).isEqualTo("7");
        assertThat(response.created()).isTrue();
        assertThat(response.admin()).isTrue();
        verify(workspaceRepository).save(argThat(ws -> ws.getOwnerUserId() == 7L
                && ws.isDefault()
                && WorkspaceConstants.DEFAULT_WORKSPACE_NAME.equals(ws.getName())
                && ws.getCreatedBy() == 7L));
        verify(workspaceMembershipRepository).save(argThat(m -> m.getWorkspaceId() == 99L
                && m.getGranteeType() == GranteeType.USER
                && m.getUserId() == 7L
                && m.getRole() == RoleCode.OWNER));
    }

    @Test
    @DisplayName("Admin이 이미 있으면 최초 로그인이라도 admin은 부여하지 않는다")
    void getOrCreateSkipsBootstrapWhenAdminExists() {
        // given
        given(userIdentityRepository.findByProviderAndProviderUserId("github", "gh-1"))
                .willReturn(Optional.empty());
        given(userRepository.countByIsAdminTrue()).willReturn(1L);
        given(userRepository.save(any(User.class))).willAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        given(workspaceRepository.save(any(Workspace.class))).willAnswer(inv -> {
            Workspace saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        // when
        GetOrCreateUserResponse response = internalAccountService.getOrCreate(githubRequest);

        // then
        assertThat(response.admin()).isFalse();
        verify(userRepository).save(argThat(u -> !u.isAdmin()));
    }

    private User user(boolean admin) {
        User user = new User("alice@x.com", "앨리스", admin);
        user.setId(7L);
        return user;
    }
}
