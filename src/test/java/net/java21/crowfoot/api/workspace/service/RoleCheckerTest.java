package net.java21.crowfoot.api.workspace.service;

import net.java21.crowfoot.api.account.domain.Role;
import net.java21.crowfoot.api.account.repository.RoleRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Workspace 인가 판정 — 존재 은닉(비멤버 404)과 Owner 검사(멤버 비Owner 403)의 경계.
 * 08-core/00-overview.md Section 1 "인가는 core 자기 DB로 완결".
 */
@ExtendWith(MockitoExtension.class)
class RoleCheckerTest {

    @Mock
    private WorkspaceMembershipQueryRepository membershipQueryRepository;
    @Mock
    private RoleRepository roleRepository;

    private RoleChecker roleChecker;

    @BeforeEach
    void setUp() {
        roleChecker = new RoleChecker(membershipQueryRepository, roleRepository);
        lenient().when(roleRepository.findByCode("OWNER")).thenReturn(Optional.of(new Role("OWNER", "소유자", 40)));
    }

    @Test
    @DisplayName("멤버면 유효 역할을 반환한다")
    void memberGetsEffectiveRole() {
        // given
        when(membershipQueryRepository.findEffectiveRole(7L, 1L))
                .thenReturn(Optional.of(new EffectiveRole("EDITOR", 30)));

        // when
        EffectiveRole role = roleChecker.requireMember(7L, 1L);

        // then
        assertThat(role.code()).isEqualTo("EDITOR");
    }

    @Test
    @DisplayName("비멤버는 존재 은닉으로 404 WORKSPACE_NOT_FOUND")
    void nonMemberGets404() {
        // given
        when(membershipQueryRepository.findEffectiveRole(7L, 1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> roleChecker.requireMember(7L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    @Test
    @DisplayName("Owner면 requireOwner를 통과한다")
    void ownerPasses() {
        // given
        when(membershipQueryRepository.findEffectiveRole(7L, 1L))
                .thenReturn(Optional.of(new EffectiveRole("OWNER", 40)));

        // when
        EffectiveRole role = roleChecker.requireOwner(7L, 1L);

        // then
        assertThat(role.code()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("멤버이지만 Owner가 아니면 403 PERMISSION_DENIED")
    void memberWithoutOwnerLevelGets403() {
        // given
        when(membershipQueryRepository.findEffectiveRole(7L, 1L))
                .thenReturn(Optional.of(new EffectiveRole("EDITOR", 30)));

        // when & then
        assertThatThrownBy(() -> roleChecker.requireOwner(7L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("비멤버의 Owner 검사도 403이 아니라 존재 은닉 404다")
    void nonMemberOwnerCheckStill404() {
        // given
        when(membershipQueryRepository.findEffectiveRole(anyLong(), anyLong())).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> roleChecker.requireOwner(7L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
    }
}
