package net.java21.crowfoot.api.workspace.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.Role;
import net.java21.crowfoot.api.account.repository.RoleRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Workspace 인가 판정 (08-core/00-overview.md Section 1 — "인가는 core 자기 DB로 완결").
 *
 * <p>유효 역할은 개인 직접 부여와 소속 팀 부여의 max 합산이며, 권한 변경은 즉시 반영된다
 * (매 요청 판정 — 별도 캐시 없음). 존재 은닉 원칙에 따라 멤버가 아닌 Workspace는
 * 존재하지 않는 것과 같은 404 WORKSPACE_NOT_FOUND로 처리한다.
 */
@Component
@RequiredArgsConstructor
public class RoleChecker {

    private static final String OWNER_CODE = "OWNER";
    private static final String EDITOR_CODE = "EDITOR";

    private final WorkspaceMembershipQueryRepository membershipQueryRepository;
    private final RoleRepository roleRepository;

    /** 유효 역할(부여 코드 + 서열 level) — 멤버가 아니면 empty */
    @Transactional(readOnly = true)
    public Optional<EffectiveRole> effectiveRole(long userId, long workspaceId) {
        return membershipQueryRepository.findEffectiveRole(userId, workspaceId);
    }

    /** 멤버 검사 — 비멤버면 존재 은닉 404 */
    @Transactional(readOnly = true)
    public EffectiveRole requireMember(long userId, long workspaceId) {
        return effectiveRole(userId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    /**
     * Owner 검사 — 비멤버는 존재 은닉 404, 멤버이지만 Owner가 아니면 403
     * (멤버인 Workspace의 권한 부족은 존재 은닉 예외 — 08-core/00-overview.md Section 1).
     */
    @Transactional(readOnly = true)
    public EffectiveRole requireOwner(long userId, long workspaceId) {
        return requireLevel(userId, workspaceId, OWNER_CODE);
    }

    /**
     * Editor 이상 검사 — 모델 생성 등 (08-core/02-model.md Section 1 최소 역할).
     * 비멤버는 존재 은닉 404, 멤버이지만 Editor 미만이면 403.
     */
    @Transactional(readOnly = true)
    public EffectiveRole requireEditor(long userId, long workspaceId) {
        return requireLevel(userId, workspaceId, EDITOR_CODE);
    }

    private EffectiveRole requireLevel(long userId, long workspaceId, String code) {
        EffectiveRole role = requireMember(userId, workspaceId);
        int minLevel = roleRepository.findByCode(code)
                .map(Role::getLevel)
                .orElseThrow(() -> new IllegalStateException("roles 시드 데이터에 " + code + "가 없습니다"));
        if (role.level() < minLevel) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        return role;
    }
}
