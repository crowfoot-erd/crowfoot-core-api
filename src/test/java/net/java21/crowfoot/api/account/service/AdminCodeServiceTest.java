package net.java21.crowfoot.api.account.service;

import net.java21.crowfoot.api.account.domain.Provider;
import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.account.domain.Role;
import net.java21.crowfoot.api.account.dto.AdminDatabaseTypeResponse;
import net.java21.crowfoot.api.account.dto.AdminProviderResponse;
import net.java21.crowfoot.api.account.dto.AdminRoleResponse;
import net.java21.crowfoot.api.account.dto.UpdateAdminDatabaseTypeRequest;
import net.java21.crowfoot.api.account.dto.UpdateAdminProviderRequest;
import net.java21.crowfoot.api.account.dto.UpdateAdminRoleRequest;
import net.java21.crowfoot.api.account.repository.ProviderRepository;
import net.java21.crowfoot.api.account.repository.RoleRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** 관리자 코드 테이블 (08-core/05-account.md Section 2.5~2.6) — 표시명·활성만, level 변경 400 */
@ExtendWith(MockitoExtension.class)
class AdminCodeServiceTest {

    @Mock
    private AdminGuard adminGuard;
    @Mock
    private ProviderRepository providerRepository;
    @Mock
    private net.java21.crowfoot.api.model.repository.DatabaseTypeRepository databaseTypeRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private AdminCodeService adminCodeService;

    @Test
    @DisplayName("databaseTypes 조회는 활성 여부와 관계없이 전체를 내려준다")
    void databaseTypesListsAllIncludingInactive() {
        // given
        given(databaseTypeRepository.findAllByOrderByCodeAsc()).willReturn(List.of(
                new DatabaseType("mysql", "MySQL", true),
                new DatabaseType("postgresql", "PostgreSQL", false)));

        // when
        List<AdminDatabaseTypeResponse> responses = adminCodeService.databaseTypes(2L).responses();

        // then
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).code()).isEqualTo("mysql");
        assertThat(responses.get(1).isActive()).isFalse();
    }

    @Test
    @DisplayName("databaseType 수정은 표시명·활성을 반영해 수정된 항목 1건을 반환한다")
    void updateDatabaseTypeAppliesChangesAndReturnsItem() {
        // given
        DatabaseType mysql = new DatabaseType("mysql", "MySQL", true);
        given(databaseTypeRepository.findById("mysql")).willReturn(Optional.of(mysql));

        // when
        AdminDatabaseTypeResponse response = adminCodeService.updateDatabaseType(2L,
                new UpdateAdminDatabaseTypeRequest("mysql", "MySQL 8", false));

        // then
        assertThat(response.displayName()).isEqualTo("MySQL 8");
        assertThat(response.isActive()).isFalse();
        verify(auditRecorder).record(eq(2L), eq("DATABASE_TYPE_UPDATED"), eq("DATABASE_TYPE"), eq("mysql"), any());
    }

    @Test
    @DisplayName("providers 조회는 활성 여부와 관계없이 전체를 내려준다")
    void providersListsAllIncludingInactive() {
        // given
        given(providerRepository.findAllByOrderByCodeAsc()).willReturn(List.of(
                new Provider("github", "GitHub", true),
                new Provider("google", "Google", false)));

        // when
        List<AdminProviderResponse> responses = adminCodeService.providers(2L).responses();

        // then
        assertThat(responses).hasSize(2);
        assertThat(responses.get(1).code()).isEqualTo("google");
        assertThat(responses.get(1).isActive()).isFalse();
    }

    @Test
    @DisplayName("provider 수정은 표시명·활성을 반영해 수정된 항목 1건을 반환한다")
    void updateProviderAppliesChangesAndReturnsItem() {
        // given
        Provider google = new Provider("google", "Google", true);
        given(providerRepository.findById("google")).willReturn(Optional.of(google));

        // when
        AdminProviderResponse response = adminCodeService.updateProvider(2L,
                new UpdateAdminProviderRequest("google", "구글", false));

        // then
        assertThat(response.displayName()).isEqualTo("구글");
        assertThat(response.isActive()).isFalse();
        assertThat(google.getDisplayName()).isEqualTo("구글");
        assertThat(google.isActive()).isFalse();
        verify(auditRecorder).record(2L, "PROVIDER_UPDATED", "PROVIDER", "google",
                java.util.Map.of("isActive", false));
    }

    @Test
    @DisplayName("없는 provider code는 404 RESOURCE_NOT_FOUND")
    void updateProviderRejectsUnknownCode() {
        // given
        given(providerRepository.findById("kakao")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminCodeService.updateProvider(2L,
                new UpdateAdminProviderRequest("kakao", "카카오", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("roles 조회는 level을 포함해 내려준다(UI는 미노출)")
    void rolesListsWithLevel() {
        // given
        given(roleRepository.findAllByOrderByLevelDesc()).willReturn(List.of(
                new Role("OWNER", "소유자", 40),
                new Role("VIEWER", "뷰어", 10)));

        // when
        List<AdminRoleResponse> responses = adminCodeService.roles(2L).responses();

        // then
        assertThat(responses).extracting(AdminRoleResponse::level).containsExactly(40, 10);
    }

    @Test
    @DisplayName("role 표시명 변경은 수정된 항목을 반환한다")
    void updateRoleChangesDisplayNameOnly() {
        // given
        Role viewer = new Role("VIEWER", "뷰어", 10);
        given(roleRepository.findById("VIEWER")).willReturn(Optional.of(viewer));

        // when
        AdminRoleResponse response = adminCodeService.updateRole(2L,
                new UpdateAdminRoleRequest("VIEWER", "열람자", null));

        // then
        assertThat(response.displayName()).isEqualTo("열람자");
        assertThat(viewer.getDisplayName()).isEqualTo("열람자");
        assertThat(viewer.getLevel()).isEqualTo(10); // 불변
        verify(auditRecorder).record(2L, "ROLE_UPDATED", "ROLE", "VIEWER", null);
    }

    @Test
    @DisplayName("level 포함 요청은 400 INVALID_REQUEST — 대상 조회 전에 거부한다")
    void updateRoleRejectsLevelChange() {
        // when & then
        assertThatThrownBy(() -> adminCodeService.updateRole(2L,
                new UpdateAdminRoleRequest("VIEWER", "열람자", 50)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verify(roleRepository, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.anyString());
    }
}
