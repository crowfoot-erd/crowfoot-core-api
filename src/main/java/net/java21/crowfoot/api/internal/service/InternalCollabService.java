package net.java21.crowfoot.api.internal.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.dto.MeResponse;
import net.java21.crowfoot.api.account.service.AccountService;
import net.java21.crowfoot.api.internal.dto.CollabMembershipResponse;
import net.java21.crowfoot.api.internal.dto.CollabProfileResponse;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 협업 서버(crowfoot-collab) 지원 내부 API (05-editor/03-collaboration.md Section 2.1) —
 * 룸(=Model) 인가를 위한 멤버십 조회와, introspection이 주지 않는 표시 프로필 조회.
 *
 * <p>둘 다 읽기 전용이며 Gateway 라우팅 제외, 내부망에서만 연다(Internal* 관례).
 * 인가 판정 자체는 호출부가 응답의 role로 수행한다 — 비멤버는 404 대신 role "NONE"으로
 * 알려 거부 사유를 구분하지 않는다(존재 은닉).
 */
@Service
@RequiredArgsConstructor
public class InternalCollabService {

    private final ModelRepository modelRepository;
    private final RoleChecker roleChecker;
    private final AccountService accountService;

    /** 문서 소속 Workspace의 유효 역할 — 문서 없음은 404, 비멤버는 role "NONE" */
    @Transactional(readOnly = true)
    public CollabMembershipResponse membership(long modelId, long userId) {
        Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        String role = roleChecker.effectiveRole(userId, model.getWorkspaceId())
                .map(EffectiveRole::code)
                .orElse(CollabMembershipResponse.ROLE_NONE);
        return CollabMembershipResponse.of(model.getWorkspaceId(), role);
    }

    /** 표시 프로필(이름·아바타·핸들) — /me의 도출 규칙(GitHub 아바타 계산·핸들 저장값)을 그대로 재사용 */
    @Transactional(readOnly = true)
    public CollabProfileResponse profile(long userId) {
        MeResponse me = accountService.me(userId);
        return new CollabProfileResponse(me.userId(), me.name(), me.avatarUrl(), me.githubLogin());
    }
}
