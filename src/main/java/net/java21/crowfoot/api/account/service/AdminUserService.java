package net.java21.crowfoot.api.account.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.domain.UserIdentity;
import net.java21.crowfoot.api.account.dto.AdminIdentityResponse;
import net.java21.crowfoot.api.account.dto.AdminPageParams;
import net.java21.crowfoot.api.account.dto.AdminUserResponse;
import net.java21.crowfoot.api.account.repository.UserIdentityQueryRepository;
import net.java21.crowfoot.api.account.repository.UserQueryRepository;
import net.java21.crowfoot.api.account.repository.UserQueryRepository.AdminUserRow;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 관리자 — 사용자 목록·상세 (08-core/05-account.md Section 2.1~2.2).
 *
 * <p>목록은 탈퇴 사용자도 포함(soft 방식)하며 userId 오름차순 고정,
 * identities(제공자 연동)는 페이지 사용자 전체를 한 번에(IN) 조회해 조립한다(N+1 회피).
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AdminGuard adminGuard;
    private final UserQueryRepository userQueryRepository;
    private final UserIdentityQueryRepository userIdentityQueryRepository;
    private final UserRepository userRepository;
    private final AuditRecorder auditRecorder;

    /** 전체 사용자 목록 — keyword(이름·이메일 부분 일치)·페이징 */
    @Transactional(readOnly = true)
    public ListApiResponse<AdminUserResponse> users(long adminId, String keyword, Integer page, Integer size) {
        adminGuard.requireAdmin(adminId);
        String trimmed = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        AdminPageParams params = AdminPageParams.of(page, size);

        long total = userQueryRepository.countAdminUsers(trimmed);
        List<AdminUserRow> rows = total == 0 ? List.of()
                : userQueryRepository.searchAdminUsers(trimmed, params.offset(), params.size());

        auditRecorder.record(adminId, "ADMIN_USERS_LISTED", "USER", "ALL",
                trimmed == null ? null : Map.of("keyword", trimmed));
        return ListApiResponse.paged(assemble(rows), params.page(), params.size(), total);
    }

    /** 사용자 상세 — 목록과 같은 필드 단건. 비숫자·없는 ID는 같은 404(존재 은닉) */
    @Transactional(readOnly = true)
    public AdminUserResponse user(long adminId, String userId) {
        adminGuard.requireAdmin(adminId);
        long id = parseUserId(userId);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<AdminIdentityResponse> identities = userIdentityQueryRepository.findByUserId(id).stream()
                .map(AdminUserService::toIdentity)
                .toList();

        auditRecorder.record(adminId, "ADMIN_USER_VIEWED", "USER", Long.toString(id), null);
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getName(),
                identities, user.isAdmin(), user.getWithdrawnAt(), user.getCreatedAt());
    }

    private List<AdminUserResponse> assemble(List<AdminUserRow> rows) {
        List<Long> ids = rows.stream().map(AdminUserRow::userId).toList();
        Map<Long, List<AdminIdentityResponse>> identitiesByUser = userIdentityQueryRepository.findByUserIdIn(ids).stream()
                .collect(Collectors.groupingBy(UserIdentity::getUserId,
                        Collectors.mapping(AdminUserService::toIdentity, Collectors.toList())));
        return rows.stream()
                .map(row -> new AdminUserResponse(row.userId(), row.email(), row.name(),
                        identitiesByUser.getOrDefault(row.userId(), List.of()),
                        row.admin(), row.withdrawnAt(), row.createdAt()))
                .toList();
    }

    private static AdminIdentityResponse toIdentity(UserIdentity identity) {
        return new AdminIdentityResponse(identity.getProvider(), identity.getProviderUserId());
    }

    private static long parseUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
