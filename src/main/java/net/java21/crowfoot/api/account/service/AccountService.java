package net.java21.crowfoot.api.account.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.domain.UserIdentity;
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
        List<UserIdentity> identities = userIdentityQueryRepository.findByUserId(userId);
        List<String> providers = identities.stream()
                .map(identity -> identity.getProvider())
                .toList();
        return new MeResponse(Long.toString(user.getId()), user.getEmail(), user.getName(),
                githubAvatarUrl(identities), githubLogin(identities), providers, user.isAdmin(),
                user.getLocale(), user.getCreatedAt());
    }

    /** UI 언어 설정 (08-core/05-account.md Section 1.4) — 멱등(값이 같아도 200), 갱신된 프로필을 돌려준다 */
    @Transactional
    public MeResponse updateLocale(long userId, String locale) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        user.setLocale(locale);
        auditRecorder.record(userId, "USER_LOCALE_UPDATED", "USER", Long.toString(userId), Map.of("locale", locale));
        return me(userId);
    }

    /** GitHub 프로필 사진 URL — 저장 없이 identity의 제공자 사용자 ID(숫자)에서 도출한다.
     *  GitHub 아바타 CDN은 계정 ID로 이미지를 serve하며(avatars.githubusercontent.com/u/{id}) 항상
     *  최신 프로필 사진을 돌려준다. Google은 공개된 ID→사진 규칙이 없어 null(웹 이니셜 폴백). */
    private String githubAvatarUrl(List<UserIdentity> identities) {
        return identities.stream()
                .filter(identity -> "github".equals(identity.getProvider()))
                .findFirst()
                .map(identity -> "https://avatars.githubusercontent.com/u/" + identity.getProviderUserId() + "?v=4")
                .orElse(null);
    }

    /** GitHub 핸들(login) — avatarUrl과 달리 숫자 ID에서 도출 불가라 로그인 시 저장된 값을 돌려준다.
     *  Google은 핸들이 없어 null(웹은 @표시 생략). */
    private String githubLogin(List<UserIdentity> identities) {
        return identities.stream()
                .filter(identity -> "github".equals(identity.getProvider()))
                .findFirst()
                .map(UserIdentity::getProviderUsername)
                .orElse(null);
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
