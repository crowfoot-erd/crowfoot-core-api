package net.java21.crowfoot.api.account.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/** 관리자 인가 판정 — users.is_admin 최종 판정, 탈퇴·없는 사용자·비Admin은 같은 403(존재 은닉) */
@ExtendWith(MockitoExtension.class)
class AdminGuardTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminGuard adminGuard;

    @Test
    @DisplayName("활동 중인 Admin은 통과한다")
    void passesActiveAdmin() {
        // given
        given(userRepository.findById(2L)).willReturn(Optional.of(user(true, false)));

        // when & then
        assertThatCode(() -> adminGuard.requireAdmin(2L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Admin이 아니면 403 PERMISSION_DENIED")
    void rejectsNonAdmin() {
        // given
        given(userRepository.findById(5L)).willReturn(Optional.of(user(false, false)));

        // when & then
        assertThatThrownBy(() -> adminGuard.requireAdmin(5L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("탈퇴한 Admin도 403 — 잔여 토큰으로의 관리 액션 차단")
    void rejectsWithdrawnAdmin() {
        // given
        given(userRepository.findById(2L)).willReturn(Optional.of(user(true, true)));

        // when & then
        assertThatThrownBy(() -> adminGuard.requireAdmin(2L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("없는 사용자도 403 — 존재를 구분해 노출하지 않는다")
    void rejectsUnknownUser() {
        // given
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminGuard.requireAdmin(99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    private User user(boolean admin, boolean withdrawn) {
        User user = new User("admin@x.com", "관리자", admin);
        user.setId(2L);
        if (withdrawn) {
            user.setWithdrawnAt(Instant.parse("2026-09-05T00:00:00Z"));
        }
        return user;
    }
}
