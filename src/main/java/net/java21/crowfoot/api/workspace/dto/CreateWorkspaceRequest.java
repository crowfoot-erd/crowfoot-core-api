package net.java21.crowfoot.api.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 개인 소유 Workspace 생성 요청 (08-core/01-workspace.md Section 1.2).
 */
public record CreateWorkspaceRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description
) {
}
