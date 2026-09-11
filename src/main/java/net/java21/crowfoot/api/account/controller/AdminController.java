package net.java21.crowfoot.api.account.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.dto.AdminDatabaseTypeResponse;
import net.java21.crowfoot.api.account.dto.AdminProviderResponse;
import net.java21.crowfoot.api.account.dto.AdminRoleResponse;
import net.java21.crowfoot.api.account.dto.AdminSessionResponse;
import net.java21.crowfoot.api.account.dto.AdminUserResponse;
import net.java21.crowfoot.api.account.dto.AuditLogResponse;
import net.java21.crowfoot.api.account.dto.UpdateAdminDatabaseTypeRequest;
import net.java21.crowfoot.api.account.dto.UpdateAdminProviderRequest;
import net.java21.crowfoot.api.account.dto.UpdateAdminRoleRequest;
import net.java21.crowfoot.api.account.service.AdminAuditService;
import net.java21.crowfoot.api.account.service.AdminCodeService;
import net.java21.crowfoot.api.account.service.AdminSessionService;
import net.java21.crowfoot.api.account.service.AdminUserService;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 API (08-core/05-account.md Section 2) — 구현 경로 /core/admin/**
 * (Gateway URL Rewrite 후 — 외부 계약은 /api/v1/core/admin/*).
 * 인가는 각 서비스가 AdminGuard로 최종 판정한다(users.is_admin).
 */
@RestController
@RequiredArgsConstructor
public class AdminController {

    private final AdminUserService adminUserService;
    private final AdminSessionService adminSessionService;
    private final AdminCodeService adminCodeService;
    private final AdminAuditService adminAuditService;

    /** 전체 사용자 목록 — keyword·페이징 */
    @GetMapping("/core/admin/users")
    public ListApiResponse<AdminUserResponse> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return adminUserService.users(CurrentUserHolder.get().userId(), keyword, page, size);
    }

    @GetMapping("/core/admin/users/{user-id}")
    public ApiResponse<AdminUserResponse> user(@PathVariable("user-id") String userId) {
        return ApiResponse.success(adminUserService.user(CurrentUserHolder.get().userId(), userId));
    }

    /** 사용자 활성 세션 목록 — userId 필수 */
    @GetMapping("/core/admin/sessions")
    public ListApiResponse<AdminSessionResponse> sessions(
            @RequestParam String userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return adminSessionService.sessions(CurrentUserHolder.get().userId(), userId, page, size);
    }

    /** 세션 개별 폐기 — 본문 없음 */
    @DeleteMapping("/core/admin/sessions/{sid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable("sid") String sid) {
        adminSessionService.revokeSession(CurrentUserHolder.get().userId(), sid);
    }

    @GetMapping("/core/admin/providers")
    public ListApiResponse<AdminProviderResponse> providers() {
        return adminCodeService.providers(CurrentUserHolder.get().userId());
    }

    @GetMapping("/core/admin/database-types")
    public ListApiResponse<AdminDatabaseTypeResponse> databaseTypes() {
        return adminCodeService.databaseTypes(CurrentUserHolder.get().userId());
    }

    @PatchMapping("/core/admin/database-types")
    public ApiResponse<AdminDatabaseTypeResponse> updateDatabaseType(
            @Valid @RequestBody UpdateAdminDatabaseTypeRequest request) {
        return ApiResponse.success(
                adminCodeService.updateDatabaseType(CurrentUserHolder.get().userId(), request));
    }

    @PatchMapping("/core/admin/providers")
    public ApiResponse<AdminProviderResponse> updateProvider(@Valid @RequestBody UpdateAdminProviderRequest request) {
        return ApiResponse.success(adminCodeService.updateProvider(CurrentUserHolder.get().userId(), request));
    }

    @GetMapping("/core/admin/roles")
    public ListApiResponse<AdminRoleResponse> roles() {
        return adminCodeService.roles(CurrentUserHolder.get().userId());
    }

    @PatchMapping("/core/admin/roles")
    public ApiResponse<AdminRoleResponse> updateRole(@Valid @RequestBody UpdateAdminRoleRequest request) {
        return ApiResponse.success(adminCodeService.updateRole(CurrentUserHolder.get().userId(), request));
    }

    /** 감사 로그 목록 — keyword(주체 이름·이메일)·action(정확 일치)·페이징, 최신순 */
    @GetMapping("/core/admin/audit-logs")
    public ListApiResponse<AuditLogResponse> auditLogs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return adminAuditService.logs(CurrentUserHolder.get().userId(), keyword, action, page, size);
    }
}
