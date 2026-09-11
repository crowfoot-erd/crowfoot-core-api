package net.java21.crowfoot.api.account.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.MeResponse;
import net.java21.crowfoot.api.account.repository.UserIdentityQueryRepository;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository;
import net.java21.crowfoot.api.client.AuthBlacklistClient;
import net.java21.crowfoot.api.team.repository.TeamQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 계정 API (08-core/05-account.md Section 1) — 내 프로필·회원 탈퇴.
 *
 * <p>탈퇴는 soft(withdrawn_at 기록): 사전 조건(남아 있는 소유권)을 검사하고,
 * 한 트랜잭션에서 withdrawn_at 기록 + 본인 Refresh lineage 전량 폐기를 수행하며,
 * 커밋 전에 인증 서버 블랙리스트에 본인 활성 세션 sid 전량을 등록한다(fail-closed).
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository userRepository;
    private final UserIdentityQueryRepository userIdentityQueryRepository;
    private final RefreshTokenQueryRepository refreshTokenQueryRepository;
    private final WorkspaceMembershipQueryRepository workspaceMembershipQueryRepository;
    private final TeamQueryRepository teamQueryRepository;
    private final AuthBlacklistClient authBlacklistClient;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    @Transactional(readOnly = true)
    public MeResponse me(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<String> providers = userIdentityQueryRepository.findByUserId(userId).stream()
                .map(identity -> identity.getProvider())
                .toList();
        return new MeResponse(Long.toString(user.getId()), user.getEmail(), user.getName(),
                providers, user.isAdmin(), user.getCreatedAt());
    }

    /** 회원 탈퇴(soft) — 204 또는 409 WITHDRAW_BLOCKED. 데이터는 전부 보존한다. */
    @Transactional
    public void withdraw(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        checkWithdrawPreconditions(userId);

        List<java.util.UUID> activeSessionIds = refreshTokenQueryRepository.findActiveSessionIds(userId);
        for (java.util.UUID sid : activeSessionIds) {
            // 등록 성공 후 커밋(fail-closed) — 실패 시 SERVICE_UNAVAILABLE으로 롤백
            authBlacklistClient.registerSessionBlacklist(sid.toString());
        }
        refreshTokenQueryRepository.revokeAllByUserId(userId, clock.instant());
        user.setWithdrawnAt(clock.instant());
        auditRecorder.record(userId, "USER_WITHDRAWN", "USER", Long.toString(userId),
                Map.of("revokedSessions", activeSessionIds.size()));
    }

    /** 사전 조건 — 남아 있는 소유권이 있으면 409 WITHDRAW_BLOCKED (정리 후 재시도) */
    private void checkWithdrawPreconditions(long userId) {
        long otherGrants = workspaceMembershipQueryRepository.countOtherGrantsInOwnerWorkspaces(userId);
        if (otherGrants > 0) {
            throw new BusinessException(ErrorCode.WITHDRAW_BLOCKED);
        }
        if (teamQueryRepository.existsOwnedTeam(userId)) {
            throw new BusinessException(ErrorCode.WITHDRAW_BLOCKED);
        }
    }
}
