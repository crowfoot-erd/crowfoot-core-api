package net.java21.crowfoot.api.managed.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.managed.dto.IssueManagedDatabaseRequest;
import net.java21.crowfoot.api.managed.dto.ManagedCredentialResponse;
import net.java21.crowfoot.api.managed.dto.ManagedDatabaseListResponse;
import net.java21.crowfoot.api.managed.dto.ManagedDatabaseResponse;
import net.java21.crowfoot.api.managed.service.ManagedDatabaseService;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 매니지드 발급 API (08-core/07-managed-database.md Section 3.5~3.6) —
 * 구현 경로 /core/workspaces/{id}/managed-databases (Gateway URL Rewrite 후 —
 * 외부 계약은 /api/v1/core/*). 목록은 멤버 전체, 발급·철회는 Editor 이상.
 */
@RestController
@RequiredArgsConstructor
public class ManagedDatabaseController {

    private final ManagedDatabaseService managedDatabaseService;

    /** 발급 목록 + 요청자 기준 인스턴스별 한도 요약 */
    @GetMapping("/core/workspaces/{workspace-id}/managed-databases")
    public ManagedDatabaseListResponse list(@PathVariable("workspace-id") long workspaceId) {
        return managedDatabaseService.list(CurrentUserHolder.get().userId(), workspaceId);
    }

    /** 발급 — 스키마 생성 + 커넥션 자동 등록(instanceId 생략 시 활성 첫 번째) */
    @PostMapping("/core/workspaces/{workspace-id}/managed-databases")
    public ResponseEntity<ApiResponse<ManagedDatabaseResponse>> issue(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody(required = false) IssueManagedDatabaseRequest request) {
        ManagedDatabaseResponse response = managedDatabaseService.issue(
                CurrentUserHolder.get().userId(), workspaceId,
                request == null ? new IssueManagedDatabaseRequest(null) : request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId
                        + "/managed-databases/" + response.databaseId()))
                .body(ApiResponse.success(response));
    }

    /** 철회 — 스키마 DROP CASCADE + 발급 커넥션 삭제(본인 발급만) */
    @DeleteMapping("/core/workspaces/{workspace-id}/managed-databases/{database-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("database-id") long databaseId) {
        managedDatabaseService.revoke(CurrentUserHolder.get().userId(), workspaceId, databaseId);
    }

    /** 접속 정보 조회(본인 발급만) — 접속 주소·계정·비밀번호. 외부 클라이언트 접속용 */
    @GetMapping("/core/workspaces/{workspace-id}/managed-databases/{database-id}/credential")
    public ApiResponse<ManagedCredentialResponse> credential(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("database-id") long databaseId) {
        return ApiResponse.success(
                managedDatabaseService.credential(CurrentUserHolder.get().userId(), workspaceId, databaseId));
    }
}
