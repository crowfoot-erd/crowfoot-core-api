package net.java21.crowfoot.api.account.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.domain.UserIdentity;
import net.java21.crowfoot.api.account.dto.AdminIdentityResponse;
import net.java21.crowfoot.api.account.dto.AdminUserResponse;
import net.java21.crowfoot.api.account.repository.UserIdentityQueryRepository;
import net.java21.crowfoot.api.account.repository.UserQueryRepository;
import net.java21.crowfoot.api.account.repository.UserQueryRepository.AdminUserRow;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 관리자 사용자 목록·상세 (08-core/05-account.md Section 2.1~2.2) — identities 일괄 조립·탈퇴자 포함 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final Instant JOINED_AT = Instant.parse("2026-09-04T01:00:00Z");

    @Mock
    private AdminGuard adminGuard;
    @Mock
    private UserQueryRepository userQueryRepository;
    @Mock
    private UserIdentityQueryRepository userIdentityQueryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private AdminUserService adminUserService;

    @Test
    @DisplayName("목록은 guard를 통과한 뒤 페이지 행 + 일괄 IN 조회로 identities를 조립한다")
    void usersAssemblesProvidersInOneQuery() {
        // given
        AdminUserRow kim = new AdminUserRow(3L, "kim@x.com", "김철수", false, null, JOINED_AT);
        AdminUserRow old = new AdminUserRow(4L, "old@x.com", "옛날철수", false,
                Instant.parse("2026-09-05T00:00:00Z"), JOINED_AT); // 탈퇴자도 포함
        given(userQueryRepository.countAdminUsers("철")).willReturn(2L);
        given(userQueryRepository.searchAdminUsers("철", 0L, 20)).willReturn(List.of(kim, old));
        given(userIdentityQueryRepository.findByUserIdIn(List.of(3L, 4L))).willReturn(List.of(
                new UserIdentity(3L, "github", "gh-1", null, "kim@x.com", "김철수"),
                new UserIdentity(3L, "google", "g-1", null, "kim@x.com", "김철수")));

        // when
        ListApiResponse<AdminUserResponse> result = adminUserService.users(2L, "철", 1, 20);

        // then
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.responses()).hasSize(2);
        assertThat(result.responses().get(0).identities()).containsExactly(
                new AdminIdentityResponse("github", "gh-1"),
                new AdminIdentityResponse("google", "g-1"));
        assertThat(result.responses().get(1).identities()).isEmpty(); // 연동 없음 — 빈 배열
        assertThat(result.responses().get(1).withdrawnAt()).isNotNull();
        verify(auditRecorder).record(2L, "ADMIN_USERS_LISTED", "USER", "ALL",
                java.util.Map.of("keyword", "철"));
    }

    @Test
    @DisplayName("빈 keyword는 전체 조회(null 전달)로 처리한다")
    void blankKeywordMeansAllUsers() {
        // given
        given(userQueryRepository.countAdminUsers(null)).willReturn(0L);

        // when
        ListApiResponse<AdminUserResponse> result = adminUserService.users(2L, "  ", null, null);

        // then
        assertThat(result.totalCount()).isZero();
        assertThat(result.responses()).isEmpty();
        verify(userQueryRepository, never()).searchAdminUsers(anyString(), anyLong(), anyInt());
        verify(auditRecorder).record(2L, "ADMIN_USERS_LISTED", "USER", "ALL", null);
    }

    @Test
    @DisplayName("size·page는 경계로 정규화한다 — 기본 20·상한 100·page 최소 1")
    void normalizesPagingParams() {
        // given
        given(userQueryRepository.countAdminUsers(null)).willReturn(1L);
        given(userQueryRepository.searchAdminUsers(null, 0L, 100))
                .willReturn(List.of(new AdminUserRow(3L, "kim@x.com", "김철수", false, null, JOINED_AT)));

        // when: size 500 → 100(상한), page 0 → 1
        ListApiResponse<AdminUserResponse> result = adminUserService.users(2L, null, 0, 500);

        // then
        assertThat(result.size()).isEqualTo(100);
        assertThat(result.page()).isEqualTo(1);
        verify(userQueryRepository).searchAdminUsers(null, 0L, 100);
    }

    @Test
    @DisplayName("상세는 없는 사용자와 비숫자 ID를 같은 404 RESOURCE_NOT_FOUND로 처리한다(존재 은닉)")
    void userHidesUnknownAndNonNumericIds() {
        // when & then
        assertThatThrownBy(() -> adminUserService.user(2L, "abc"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        given(userRepository.findById(99L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> adminUserService.user(2L, "99"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("상세는 목록과 같은 필드로 단건 응답한다")
    void userReturnsSameFieldsAsList() {
        // given
        User user = new User("kim@x.com", "김철수", false);
        user.setId(3L);
        given(userRepository.findById(3L)).willReturn(Optional.of(user));
        given(userIdentityQueryRepository.findByUserId(3L)).willReturn(List.of(
                new UserIdentity(3L, "github", "gh-1", null, "kim@x.com", "김철수")));

        // when
        AdminUserResponse response = adminUserService.user(2L, "3");

        // then
        assertThat(response.userId()).isEqualTo("3");
        assertThat(response.identities()).containsExactly(new AdminIdentityResponse("github", "gh-1"));
        assertThat(response.admin()).isFalse();
        assertThat(response.withdrawnAt()).isNull();
        verify(auditRecorder).record(2L, "ADMIN_USER_VIEWED", "USER", "3", null);
    }
}
