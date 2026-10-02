package net.java21.crowfoot.api.workspace.dto;

import net.java21.crowfoot.api.account.dto.UserRefResponse;

import java.time.Instant;

/**
 * Workspace 상세 응답 (08-core/01-workspace.md — 상세 응답 필드).
 */
public record WorkspaceResponse(
        String workspaceId,
        String name,
        String description,
        boolean isDefault,
        int memberCount,
        UserRefResponse createdBy,
        Instant createdAt,
        /** 요청한 사용자의 유효 역할 코드(OWNER·EDITOR·COMMENTER·VIEWER) */
        String myRole
) {
}
