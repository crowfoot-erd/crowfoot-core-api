package net.java21.crowfoot.api.managed.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.managed.dto.CreateManagedInstanceRequest;
import net.java21.crowfoot.api.managed.dto.ManagedInstanceResponse;
import net.java21.crowfoot.api.managed.dto.ManagedIssueLimitResponse;
import net.java21.crowfoot.api.managed.dto.SetManagedIssueLimitRequest;
import net.java21.crowfoot.api.managed.dto.UpdateManagedInstanceRequest;
import net.java21.crowfoot.api.managed.service.ManagedDatabaseService;
import net.java21.crowfoot.api.managed.service.ManagedInstanceService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 매니지드 인스턴스 관리 API (08-core/07-managed-database.md Section 3.2~3.4) —
 * 구현 경로 /core/admin/managed-instances (Gateway URL Rewrite 후 — 외부 계약은
 * /api/v1/core/admin/*). 인가는 AdminGuard가 최종 판정한다(users.is_admin).
 * 응답에 password는 어떤 형태로도 포함하지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class ManagedInstanceController {

    private final ManagedInstanceService managedInstanceService;
    private final ManagedDatabaseService managedDatabaseService;

    /** 발급 한도 조회(관리자) — 워크스페이스 내 사용자당(모든 인스턴스 합산) */
    @GetMapping("/core/admin/managed-instances/issue-limit")
    public ApiResponse<ManagedIssueLimitResponse> issueLimit() {
        return ApiResponse.success(
                new ManagedIssueLimitResponse(managedDatabaseService.issueLimit()));
    }

    /** 발급 한도 지정(관리자) — 1~100. 즉시 발급·한도 요약에 반영 */
    @PatchMapping("/core/admin/managed-instances/issue-limit")
    public ApiResponse<ManagedIssueLimitResponse> updateIssueLimit(
            @Valid @RequestBody SetManagedIssueLimitRequest request) {
        return ApiResponse.success(
                managedDatabaseService.updateIssueLimit(CurrentUserHolder.get().userId(), request));
    }

    /** 인스턴스 목록 — 등록순, 발급 수 포함 */
    @GetMapping("/core/admin/managed-instances")
    public ListApiResponse<ManagedInstanceResponse> list() {
        return managedInstanceService.list(CurrentUserHolder.get().userId());
    }

    /** 인스턴스 등록 — 제출 자격으로 SELECT 1 검증 수반(실패 시 502) */
    @PostMapping("/core/admin/managed-instances")
    public ResponseEntity<ApiResponse<ManagedInstanceResponse>> create(
            @Valid @RequestBody CreateManagedInstanceRequest request) {
        ManagedInstanceResponse response = managedInstanceService.create(
                CurrentUserHolder.get().userId(), request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/admin/managed-instances/" + response.instanceId()))
                .body(ApiResponse.success(response));
    }

    /** 인스턴스 변경 — 표시명·활성·자격(변경 시 재검증) */
    @PatchMapping("/core/admin/managed-instances/{instance-id}")
    public ApiResponse<ManagedInstanceResponse> update(
            @PathVariable("instance-id") long instanceId,
            @Valid @RequestBody UpdateManagedInstanceRequest request) {
        return ApiResponse.success(
                managedInstanceService.update(CurrentUserHolder.get().userId(), instanceId, request));
    }

    /** 인스턴스 접속 테스트 — 저장된 자격으로 SELECT 1(실패도 200 + connected:false) */
    @PostMapping("/core/admin/managed-instances/{instance-id}/test")
    public ApiResponse<net.java21.crowfoot.api.connection.dto.ConnectionTestResponse> test(
            @PathVariable("instance-id") long instanceId) {
        return ApiResponse.success(
                managedInstanceService.test(CurrentUserHolder.get().userId(), instanceId));
    }

    /** 인스턴스 삭제 — 발급이 남아 있으면 409 */
    @DeleteMapping("/core/admin/managed-instances/{instance-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("instance-id") long instanceId) {
        managedInstanceService.delete(CurrentUserHolder.get().userId(), instanceId);
    }
}
