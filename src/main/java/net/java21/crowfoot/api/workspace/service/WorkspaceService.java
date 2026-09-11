package net.java21.crowfoot.api.workspace.service;

import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.RoleCode;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.Workspace;
import net.java21.crowfoot.api.workspace.domain.WorkspaceMembership;
import net.java21.crowfoot.api.workspace.dto.CreateWorkspaceRequest;
import net.java21.crowfoot.api.workspace.dto.WorkspaceResponse;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Workspace 실체 API (08-core/01-workspace.md) — 상세·개인 소유 생성·설정 변경·삭제.
 *
 * <p>생성은 OWNER 멤버십 세트와, 삭제는 멤버십 전량 정리와 같은 트랜잭션이다(대칭).
 * 존재 은닉: 비멤버의 접근은 404 WORKSPACE_NOT_FOUND로 응답한다.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final WorkspaceMembershipQueryRepository workspaceMembershipQueryRepository;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;

    @Transactional(readOnly = true)
    public WorkspaceResponse get(long userId, long workspaceId) {
        roleChecker.requireMember(userId, workspaceId);
        return toResponse(requireWorkspace(workspaceId));
    }

    @Transactional
    public WorkspaceResponse create(long userId, CreateWorkspaceRequest request) {
        Workspace workspace = workspaceRepository.save(new Workspace(
                request.name(), request.description(), userId, false, userId));
        workspaceMembershipRepository.save(new WorkspaceMembership(
                workspace.getId(), GranteeType.USER, userId, null, RoleCode.OWNER, userId));
        auditRecorder.record(userId, "WORKSPACE_CREATED", "WORKSPACE",
                Long.toString(workspace.getId()), Map.of("name", workspace.getName()));
        return toResponse(workspace);
    }

    /**
     * 설정 변경(이름·설명) — Owner만.
     * PATCH 의미론: 필드 생략은 변경 없음, description 명시적 null은 클리어.
     */
    @Transactional
    public WorkspaceResponse patch(long userId, long workspaceId, JsonNode body) {
        roleChecker.requireOwner(userId, workspaceId);
        Workspace workspace = requireWorkspace(workspaceId);

        if (body.has("name")) {
            String name = body.get("name").asText();
            if (name == null || name.isBlank() || name.length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이름은 1~100자여야 합니다");
            }
            workspace.setName(name);
        }
        if (body.has("description")) {
            workspace.setDescription(body.get("description").isNull() ? null : body.get("description").asText());
        }
        auditRecorder.record(userId, "WORKSPACE_UPDATED", "WORKSPACE",
                Long.toString(workspaceId), null);
        return toResponse(workspace);
    }

    /** 삭제(Owner만) — 멤버십 전량 물리 DELETE 후 Workspace 물리 삭제. models CASCADE는 DB가 처리 */
    @Transactional
    public void delete(long userId, long workspaceId) {
        roleChecker.requireOwner(userId, workspaceId);
        requireWorkspace(workspaceId);
        workspaceMembershipRepository.deleteByWorkspaceId(workspaceId);
        workspaceRepository.deleteById(workspaceId);
        auditRecorder.record(userId, "WORKSPACE_DELETED", "WORKSPACE",
                Long.toString(workspaceId), null);
    }

    private Workspace requireWorkspace(long workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    private WorkspaceResponse toResponse(Workspace workspace) {
        User creator = userRepository.findById(workspace.getCreatedBy()).orElse(null);
        UserRefResponse createdBy = creator == null
                ? null
                : new UserRefResponse(Long.toString(creator.getId()), creator.getName());
        return new WorkspaceResponse(
                Long.toString(workspace.getId()),
                workspace.getName(),
                workspace.getDescription(),
                workspace.isDefault(),
                (int) workspaceMembershipQueryRepository.countDistinctMembers(workspace.getId()),
                createdBy,
                workspace.getCreatedAt());
    }
}
