package net.java21.crowfoot.api.internal.service;

import net.java21.crowfoot.api.account.dto.MeResponse;
import net.java21.crowfoot.api.account.service.AccountService;
import net.java21.crowfoot.api.internal.dto.CollabMembershipResponse;
import net.java21.crowfoot.api.internal.dto.CollabProfileResponse;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
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
import static org.mockito.BDDMockito.given;

/**
 * 협업 서버용 내부 API 단위 테스트 (05-editor/03-collaboration.md Section 2.1) —
 * 멤버십 역할 분기(OWNER/VIEWER/비멤버 NONE·문서 없음 404)와 프로필 매핑을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class InternalCollabServiceTest {

    @Mock
    private ModelRepository modelRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AccountService accountService;

    @InjectMocks
    private InternalCollabService internalCollabService;

    @Test
    @DisplayName("문서 멤버십 — 유효 역할 코드와 소속 Workspace를 돌려준다")
    void membershipReturnsEffectiveRole() {
        // given
        given(modelRepository.findById(500L)).willReturn(Optional.of(model(77L)));
        given(roleChecker.effectiveRole(7L, 77L)).willReturn(Optional.of(new EffectiveRole("OWNER", 100)));

        // when
        CollabMembershipResponse response = internalCollabService.membership(500L, 7L);

        // then
        assertThat(response.workspaceId()).isEqualTo("77");
        assertThat(response.role()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("문서 멤버십 — Viewer·Commenter도 코드 그대로(차단 판정은 호출부)")
    void membershipReturnsViewerRole() {
        // given
        given(modelRepository.findById(500L)).willReturn(Optional.of(model(77L)));
        given(roleChecker.effectiveRole(8L, 77L)).willReturn(Optional.of(new EffectiveRole("VIEWER", 10)));

        // when
        CollabMembershipResponse response = internalCollabService.membership(500L, 8L);

        // then
        assertThat(response.role()).isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("문서 멤버십 — 비멤버는 404가 아니라 role NONE(존재 은닉, 거부는 호출부)")
    void membershipReturnsNoneForNonMember() {
        // given
        given(modelRepository.findById(500L)).willReturn(Optional.of(model(77L)));
        given(roleChecker.effectiveRole(9L, 77L)).willReturn(Optional.empty());

        // when
        CollabMembershipResponse response = internalCollabService.membership(500L, 9L);

        // then
        assertThat(response.workspaceId()).isEqualTo("77");
        assertThat(response.role()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("문서 멤버십 — 문서 자체가 없으면 404 RESOURCE_NOT_FOUND")
    void membershipRejectsUnknownModel() {
        // given
        given(modelRepository.findById(500L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> internalCollabService.membership(500L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("프로필 — /me의 이름·아바타·핸들을 협업 표시용 필드로만 내린다(email 등은 제외)")
    void profileMapsDisplayFields() {
        // given
        given(accountService.me(7L)).willReturn(new MeResponse("7", "alice@x.com", "앨리스",
                "https://avatars.githubusercontent.com/u/42?v=4", "octocat",
                List.of("github"), false, "ko", Instant.parse("2026-09-01T00:00:00Z")));

        // when
        CollabProfileResponse response = internalCollabService.profile(7L);

        // then
        assertThat(response.userId()).isEqualTo("7");
        assertThat(response.name()).isEqualTo("앨리스");
        assertThat(response.avatarUrl()).isEqualTo("https://avatars.githubusercontent.com/u/42?v=4");
        assertThat(response.githubLogin()).isEqualTo("octocat");
    }

    @Test
    @DisplayName("프로필 — 없는 사용자는 /me와 같은 404로 전파된다")
    void profilePropagatesNotFound() {
        // given
        given(accountService.me(404L)).willThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // when & then
        assertThatThrownBy(() -> internalCollabService.profile(404L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Model model(long workspaceId) {
        return new Model(workspaceId, "주문 ERD", null, "mysql", "{}", 7L);
    }
}
