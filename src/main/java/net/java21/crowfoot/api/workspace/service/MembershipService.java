package net.java21.crowfoot.api.workspace.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.RoleCode;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.dto.UserSummaryResponse;
import net.java21.crowfoot.api.account.repository.UserQueryRepository;
import net.java21.crowfoot.api.account.repository.UserQueryRepository.UserCandidateResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.team.domain.Team;
import net.java21.crowfoot.api.team.repository.TeamMemberQueryRepository;
import net.java21.crowfoot.api.team.repository.TeamRepository;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.Workspace;
import net.java21.crowfoot.api.workspace.domain.WorkspaceMembership;
import net.java21.crowfoot.api.workspace.dto.ChangeRoleRequest;
import net.java21.crowfoot.api.workspace.dto.GrantMembershipRequest;
import net.java21.crowfoot.api.workspace.dto.MembershipIdResponse;
import net.java21.crowfoot.api.workspace.dto.MembershipResponse;
import net.java21.crowfoot.api.workspace.dto.MyWorkspaceResponse;
import net.java21.crowfoot.api.workspace.dto.TeamSummaryResponse;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 멤버십 API (08-core/03-membership.md) — 내 워크스페이스 목록·부여·역할 변경·회수·후보 검색.
 *
 * <p>부여·변경·회수는 Owner 전용(비멤버 404 존재 은닉, 멤버 비Owner 403).
 * 마지막 OWNER 부여 행은 강등·회수할 수 없다(LAST_OWNER_PROTECTED).
 */
@Service
@RequiredArgsConstructor
public class MembershipService {

    private static final int CANDIDATE_DEFAULT_LIMIT = 10;
    private static final int CANDIDATE_MAX_LIMIT = 20;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceQueryRepository workspaceQueryRepository;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final WorkspaceMembershipQueryRepository workspaceMembershipQueryRepository;
    private final UserRepository userRepository;
    private final UserQueryRepository userQueryRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberQueryRepository teamMemberQueryRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;

    /** 내 워크스페이스 목록 — 멤버십 × 실체 1회 조합, myRole은 유효 역할(max 합산) */
    @Transactional(readOnly = true)
    public ListApiResponse<MyWorkspaceResponse> myWorkspaces(long userId) {
        List<Long> workspaceIds = workspaceMembershipQueryRepository.findWorkspaceIdsOfUser(userId);
        List<MyWorkspaceResponse> responses = workspaceQueryRepository.findByIdIn(workspaceIds).stream()
                .map(workspace -> new MyWorkspaceResponse(
                        Long.toString(workspace.getId()),
                        workspace.getName(),
                        workspace.getDescription(),
                        workspace.isDefault(),
                        workspaceMembershipQueryRepository.findEffectiveRole(userId, workspace.getId())
                                .map(EffectiveRole::code)
                                .orElseThrow(() -> new IllegalStateException(
                                        "멤버십이 있는 워크스페이스의 유효 역할이 없습니다 — id=" + workspace.getId())),
                        (int) workspaceMembershipQueryRepository.countDistinctMembers(workspace.getId())))
                .toList();
        return ListApiResponse.of(responses);
    }

    /** 부여 행 목록 — 멤버 관리 탭 */
    @Transactional(readOnly = true)
    public ListApiResponse<MembershipResponse> list(long userId, long workspaceId) {
        roleChecker.requireMember(userId, workspaceId);
        Workspace workspace = requireWorkspace(workspaceId);
        List<MembershipResponse> responses = workspaceMembershipQueryRepository.findByWorkspaceId(workspaceId).stream()
                .map(membership -> toResponse(workspace, membership))
                .toList();
        return ListApiResponse.of(responses);
    }

    /** 부여(Owner만) — granteeType 배타·역할 가부·중복 검사 후 INSERT */
    @Transactional
    public MembershipIdResponse grant(long userId, long workspaceId, GrantMembershipRequest request) {
        roleChecker.requireOwner(userId, workspaceId);
        requireWorkspace(workspaceId);

        GranteeType granteeType = parseGranteeType(request.granteeType());
        RoleCode role = parseAssignableRole(request.role());
        Long targetUserId = null;
        Long targetTeamId = null;
        switch (granteeType) {
            case USER -> {
                targetUserId = requireId(request.userId(), "USER 부여에는 userId가 필요합니다");
                if (request.teamId() != null) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST,
                            "granteeType=USER에서는 teamId를 지정할 수 없습니다");
                }
                User user = userRepository.findById(targetUserId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다"));
                if (user.isWithdrawn()) {
                    throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다");
                }
            }
            case TEAM -> {
                targetTeamId = requireId(request.teamId(), "TEAM 부여에는 teamId가 필요합니다");
                if (request.userId() != null) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST,
                            "granteeType=TEAM에서는 userId를 지정할 수 없습니다");
                }
                teamRepository.findById(targetTeamId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.TEAM_NOT_FOUND));
            }
        }
        if (workspaceMembershipQueryRepository.existsGrant(workspaceId, granteeType, targetUserId, targetTeamId)) {
            throw new BusinessException(ErrorCode.MEMBERSHIP_DUPLICATED);
        }

        WorkspaceMembership membership = workspaceMembershipRepository.save(new WorkspaceMembership(
                workspaceId, granteeType, targetUserId, targetTeamId, role, userId));
        auditRecorder.record(userId, "MEMBERSHIP_GRANTED", "WORKSPACE",
                Long.toString(workspaceId), Map.of(
                        "membershipId", Long.toString(membership.getId()),
                        "granteeType", granteeType.name(),
                        "grantee", granteeType == GranteeType.USER
                                ? Long.toString(targetUserId) : Long.toString(targetTeamId),
                        "role", role.name()));
        return new MembershipIdResponse(Long.toString(membership.getId()));
    }

    /** 역할 변경(Owner만) — 마지막 OWNER 부여는 강등 불가 */
    @Transactional
    public MembershipResponse changeRole(long userId, long workspaceId, long membershipId, ChangeRoleRequest request) {
        roleChecker.requireOwner(userId, workspaceId);
        Workspace workspace = requireWorkspace(workspaceId);
        WorkspaceMembership membership = requireMembership(workspaceId, membershipId);

        RoleCode role = parseAssignableRole(request.role());
        protectLastOwner(workspaceId, membership, "마지막 Owner는 강등할 수 없습니다");
        membership.setRole(role);
        auditRecorder.record(userId, "MEMBERSHIP_ROLE_CHANGED", "WORKSPACE",
                Long.toString(workspaceId), Map.of(
                        "membershipId", Long.toString(membershipId),
                        "role", role.name()));
        return toResponse(workspace, membership);
    }

    /** 회수(Owner만) — 마지막 OWNER 부여는 회수 불가 */
    @Transactional
    public void revoke(long userId, long workspaceId, long membershipId) {
        roleChecker.requireOwner(userId, workspaceId);
        WorkspaceMembership membership = requireMembership(workspaceId, membershipId);

        protectLastOwner(workspaceId, membership, "마지막 Owner은 제외할 수 없습니다");
        workspaceMembershipRepository.deleteById(membership.getId());
        auditRecorder.record(userId, "MEMBERSHIP_REVOKED", "WORKSPACE",
                Long.toString(workspaceId), Map.of("membershipId", Long.toString(membershipId)));
    }

    /** 부여 후보 검색(Owner만) — keyword 2자 이상, limit 기본 10·최대 20 */
    @Transactional(readOnly = true)
    public ListApiResponse<UserCandidateResponse> candidates(
            long userId, long workspaceId, String keyword, Integer limit) {
        roleChecker.requireOwner(userId, workspaceId);
        requireWorkspace(workspaceId);

        String term = keyword == null ? null : keyword.trim();
        if (term == null || term.length() < 2) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "검색어는 2자 이상이어야 합니다");
        }
        int size = limit == null
                ? CANDIDATE_DEFAULT_LIMIT
                : Math.min(Math.max(limit, 1), CANDIDATE_MAX_LIMIT);
        return ListApiResponse.of(userQueryRepository.searchWorkspaceCandidates(term, workspaceId, size));
    }

    private Workspace requireWorkspace(long workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    private WorkspaceMembership requireMembership(long workspaceId, long membershipId) {
        return workspaceMembershipRepository.findById(membershipId)
                .filter(membership -> membership.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBERSHIP_NOT_FOUND));
    }

    /** 마지막 OWNER 부여 행 보호 — 그 행이 OWNER이고 대체 OWNER 부여가 없으면 409 */
    private void protectLastOwner(long workspaceId, WorkspaceMembership membership, String message) {
        if (membership.getRole() == RoleCode.OWNER
                && workspaceMembershipQueryRepository.countOwnerGrants(workspaceId) <= 1) {
            throw new BusinessException(ErrorCode.LAST_OWNER_PROTECTED, message);
        }
    }

    private static GranteeType parseGranteeType(String granteeType) {
        try {
            return GranteeType.valueOf(granteeType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "granteeType은 USER 또는 TEAM이어야 합니다");
        }
    }

    private static RoleCode parseAssignableRole(String role) {
        if (!RoleCode.isAssignable(role)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "role은 EDITOR, COMMENTER, VIEWER 중 하나여야 합니다");
        }
        return RoleCode.valueOf(role);
    }

    private static Long requireId(String id, String message) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, message);
        }
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, message);
        }
    }

    /**
     * 부여 행 → 응답. granteeType에 따라 user·team 중 하나만 채운다.
     * 기본 Workspace의 OWNER 부여(가입 프로비저닝 시스템 부여)는 grantedBy를 null로 응답한다.
     */
    private MembershipResponse toResponse(Workspace workspace, WorkspaceMembership membership) {
        UserSummaryResponse user = null;
        TeamSummaryResponse team = null;
        if (membership.getGranteeType() == GranteeType.USER) {
            user = userRepository.findById(membership.getUserId())
                    .map(u -> new UserSummaryResponse(Long.toString(u.getId()), u.getName(), u.getEmail()))
                    .orElse(null);
        } else {
            team = teamRepository.findById(membership.getTeamId())
                    .map(t -> new TeamSummaryResponse(
                            Long.toString(t.getId()), t.getName(),
                            (int) teamMemberQueryRepository.countByTeamId(t.getId())))
                    .orElse(null);
        }
        UserRefResponse grantedBy = null;
        if (!(workspace.isDefault() && membership.getRole() == RoleCode.OWNER)
                && membership.getGrantedBy() != null) {
            grantedBy = userRepository.findById(membership.getGrantedBy())
                    .map(u -> new UserRefResponse(Long.toString(u.getId()), u.getName()))
                    .orElse(null);
        }
        return new MembershipResponse(
                Long.toString(membership.getId()),
                membership.getGranteeType().name(),
                user,
                team,
                membership.getRole().name(),
                grantedBy,
                membership.getGrantedAt());
    }
}
