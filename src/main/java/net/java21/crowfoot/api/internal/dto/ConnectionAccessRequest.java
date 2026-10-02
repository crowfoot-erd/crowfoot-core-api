package net.java21.crowfoot.api.internal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 접근 확인 요청 (08-core/15-internal-api.md Section 2.1) — DB 매니저(crowfoot-database-manager)가 보낸다.
 *
 * @param userId      요청한 사용자 — Gateway가 붙인 X-USER-ID 값
 * @param workspaceId 요청 경로의 워크스페이스
 * @param mcpWrite    MCP로 온 쓰기 요청(샘플 데이터 넣기)이면 true — MCP 반영을 허용한 커넥션에만 통과한다
 */
public record ConnectionAccessRequest(
        @NotBlank String userId,
        @NotBlank String workspaceId,
        Boolean mcpWrite
) {
}
