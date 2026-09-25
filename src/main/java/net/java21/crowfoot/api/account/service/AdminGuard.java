package net.java21.crowfoot.api.account.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 인가 판정 (08-core/05-account.md Section 2) — users.is_admin 컬럼으로 최종 판정한다.
 *
 * <p>Admin이 아니면 403 PERMISSION_DENIED — 없는 사용자도 같은 예외(존재 은닉)로 처리한다.
 * 탈퇴한 Admin(잔여 토큰)도 거부한다.
 */
@Component
@RequiredArgsConstructor
public class AdminGuard {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public void requireAdmin(long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isAdmin() || user.isWithdrawn()) {
            throw BusinessException.of(ErrorCode.PERMISSION_DENIED, "detail.admin.denied");
        }
    }
}
