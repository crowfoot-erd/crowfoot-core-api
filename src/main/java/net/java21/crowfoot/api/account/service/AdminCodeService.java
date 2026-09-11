package net.java21.crowfoot.api.account.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.Provider;
import net.java21.crowfoot.api.account.domain.Role;
import net.java21.crowfoot.api.account.dto.AdminDatabaseTypeResponse;
import net.java21.crowfoot.api.account.dto.AdminProviderResponse;
import net.java21.crowfoot.api.account.dto.AdminRoleResponse;
import net.java21.crowfoot.api.account.dto.UpdateAdminDatabaseTypeRequest;
import net.java21.crowfoot.api.account.dto.UpdateAdminProviderRequest;
import net.java21.crowfoot.api.account.dto.UpdateAdminRoleRequest;
import net.java21.crowfoot.api.account.repository.ProviderRepository;
import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.account.repository.RoleRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 관리자 — 코드 테이블(providers·roles) 조회·수정 (08-core/05-account.md Section 2.5~2.6).
 *
 * <p>표시명·활성 토글만 허용한다 — 행 추가·삭제·level 변경은 배포 수반 행위라 미제공.
 * PATCH 응답은 수정된 항목 1건.
 */
@Service
@RequiredArgsConstructor
public class AdminCodeService {

    private final AdminGuard adminGuard;
    private final ProviderRepository providerRepository;
    private final DatabaseTypeRepository databaseTypeRepository;
    private final RoleRepository roleRepository;
    private final AuditRecorder auditRecorder;

    /** 제공자 전체(활성 포함) — code asc */
    @Transactional(readOnly = true)
    public ListApiResponse<AdminProviderResponse> providers(long adminId) {
        adminGuard.requireAdmin(adminId);
        List<AdminProviderResponse> responses = providerRepository.findAllByOrderByCodeAsc().stream()
                .map(AdminCodeService::toResponse)
                .toList();
        auditRecorder.record(adminId, "ADMIN_PROVIDERS_LISTED", "PROVIDER", "ALL", null);
        return ListApiResponse.of(responses);
    }

    /** 제공자 수정 — 표시명·활성. 없는 code는 404 */
    @Transactional
    public AdminProviderResponse updateProvider(long adminId, UpdateAdminProviderRequest request) {
        adminGuard.requireAdmin(adminId);
        Provider provider = providerRepository.findById(request.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (request.displayName() != null) {
            provider.setDisplayName(request.displayName());
        }
        if (request.isActive() != null) {
            provider.setActive(request.isActive());
        }
        auditRecorder.record(adminId, "PROVIDER_UPDATED", "PROVIDER", provider.getCode(),
                Map.of("isActive", provider.isActive()));
        return toResponse(provider);
    }

    /** 데이터베이스 종류 전체(활성 포함) — code asc */
    @Transactional(readOnly = true)
    public ListApiResponse<AdminDatabaseTypeResponse> databaseTypes(long adminId) {
        adminGuard.requireAdmin(adminId);
        List<AdminDatabaseTypeResponse> responses = databaseTypeRepository.findAllByOrderByCodeAsc().stream()
                .map(AdminCodeService::toResponse)
                .toList();
        auditRecorder.record(adminId, "ADMIN_DATABASE_TYPES_LISTED", "DATABASE_TYPE", "ALL", null);
        return ListApiResponse.of(responses);
    }

    /** 데이터베이스 종류 수정 — 표시명·활성. 없는 code는 404 */
    @Transactional
    public AdminDatabaseTypeResponse updateDatabaseType(long adminId, UpdateAdminDatabaseTypeRequest request) {
        adminGuard.requireAdmin(adminId);
        DatabaseType type = databaseTypeRepository.findById(request.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (request.displayName() != null) {
            type.setDisplayName(request.displayName());
        }
        if (request.isActive() != null) {
            type.setActive(request.isActive());
        }
        auditRecorder.record(adminId, "DATABASE_TYPE_UPDATED", "DATABASE_TYPE", type.getCode(),
                Map.of("isActive", type.isActive()));
        return toResponse(type);
    }

    /** 역할 전체 — level desc */
    @Transactional(readOnly = true)
    public ListApiResponse<AdminRoleResponse> roles(long adminId) {
        adminGuard.requireAdmin(adminId);
        List<AdminRoleResponse> responses = roleRepository.findAllByOrderByLevelDesc().stream()
                .map(AdminCodeService::toResponse)
                .toList();
        auditRecorder.record(adminId, "ADMIN_ROLES_LISTED", "ROLE", "ALL", null);
        return ListApiResponse.of(responses);
    }

    /** 역할 수정 — 표시명만. level 포함 요청은 400(배포 수반), 없는 code는 404 */
    @Transactional
    public AdminRoleResponse updateRole(long adminId, UpdateAdminRoleRequest request) {
        adminGuard.requireAdmin(adminId);
        if (request.level() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "level은 변경할 수 없습니다");
        }
        Role role = roleRepository.findById(request.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        role.setDisplayName(request.displayName());
        auditRecorder.record(adminId, "ROLE_UPDATED", "ROLE", role.getCode(), null);
        return toResponse(role);
    }

    private static AdminProviderResponse toResponse(Provider provider) {
        return new AdminProviderResponse(provider.getCode(), provider.getDisplayName(), provider.isActive());
    }

    private static AdminDatabaseTypeResponse toResponse(DatabaseType type) {
        return new AdminDatabaseTypeResponse(type.getCode(), type.getDisplayName(), type.isActive());
    }

    private static AdminRoleResponse toResponse(Role role) {
        return new AdminRoleResponse(role.getCode(), role.getDisplayName(), role.getLevel());
    }
}
