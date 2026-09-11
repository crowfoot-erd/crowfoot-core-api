package net.java21.crowfoot.api.internal.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.domain.UserIdentity;
import net.java21.crowfoot.api.account.domain.WorkspaceConstants;
import net.java21.crowfoot.api.account.repository.UserIdentityRepository;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.internal.dto.GetOrCreateUserRequest;
import net.java21.crowfoot.api.internal.dto.GetOrCreateUserResponse;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.Workspace;
import net.java21.crowfoot.api.workspace.domain.WorkspaceMembership;
import net.java21.crowfoot.api.account.domain.RoleCode;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 확보(get-or-create) 내부 API (08-core/05-account.md Section 3.1).
 *
 * <p>최초 로그인이면 프로비저닝(사용자 + 연동 + 개인 기본 Workspace + OWNER 멤버십)을
 * 한 트랜잭션으로 수행한다. 탈퇴한 계정(withdrawn_at 기록)의 재로그인은 409로 거부한다.
 */
@Service
@RequiredArgsConstructor
public class InternalAccountService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;

    @Transactional
    public GetOrCreateUserResponse getOrCreate(GetOrCreateUserRequest request) {
        return userIdentityRepository.findByProviderAndProviderUserId(request.provider(), request.providerUserId())
                .map(identity -> existing(identity.getUserId()))
                .orElseGet(() -> provision(request));
    }

    private GetOrCreateUserResponse existing(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (user.isWithdrawn()) {
            // 탈퇴한 계정 — 인증 서버는 302 ?error=USER_WITHDRAWN로 로그인을 거부한다
            throw new BusinessException(ErrorCode.USER_WITHDRAWN);
        }
        return GetOrCreateUserResponse.of(user.getId(), false, user.isAdmin());
    }

    private GetOrCreateUserResponse provision(GetOrCreateUserRequest request) {
        // Admin Bootstrap — Admin 부재 상태의 최초 GitHub 로그인에 자동 부여 (users.is_admin 컬럼 주석)
        boolean bootstrapAdmin = "github".equals(request.provider()) && userRepository.countByIsAdminTrue() == 0;

        User user = userRepository.save(new User(request.email(), request.name(), bootstrapAdmin));
        userIdentityRepository.save(new UserIdentity(user.getId(), request.provider(),
                request.providerUserId(), request.email(), request.name()));

        Workspace workspace = workspaceRepository.save(new Workspace(
                WorkspaceConstants.DEFAULT_WORKSPACE_NAME, null, user.getId(), true, user.getId()));
        workspaceMembershipRepository.save(new WorkspaceMembership(
                workspace.getId(), GranteeType.USER, user.getId(), null, RoleCode.OWNER, user.getId()));

        return GetOrCreateUserResponse.of(user.getId(), true, user.isAdmin());
    }
}
