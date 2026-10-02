package net.java21.crowfoot.api.accesstoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import net.java21.crowfoot.api.accesstoken.domain.WorkspaceAccessToken;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.IssueRequest;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.TokenResponse;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.VerifyResponse;
import net.java21.crowfoot.api.accesstoken.repository.WorkspaceAccessTokenRepository;
import net.java21.crowfoot.api.accesstoken.service.AccessTokenService;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 워크스페이스 액세스 토큰 단위 테스트 (08-core/18-access-token.md Section 3) — 발급·목록·폐기·검증 */
@ExtendWith(MockitoExtension.class)
class AccessTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");

    @Mock
    private WorkspaceAccessTokenRepository tokenRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;

    private AccessTokenService service;

    @BeforeEach
    void setUp() {
        service = new AccessTokenService(tokenRepository, userRepository, roleChecker, auditRecorder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WorkspaceAccessToken token(long id, long userId, Instant expiresAt) {
        WorkspaceAccessToken token = new WorkspaceAccessToken(77L, userId, "노트북", "cfw_abcdefgh", "h".repeat(64), expiresAt, NOW);
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

    private static User user(long id, boolean withdrawn) {
        User user = Mockito.mock(User.class);
        Mockito.lenient().when(user.getId()).thenReturn(id);
        Mockito.lenient().when(user.getName()).thenReturn("사용자" + id);
        Mockito.lenient().when(user.isWithdrawn()).thenReturn(withdrawn);
        return user;
    }

    @Test
    @DisplayName("발급 — 원문은 cfw_ 접두이고 응답에서만 나온다. 저장하는 것은 해시와 앞 12자다")
    void issue() {
        given(roleChecker.requireMember(2L, 77L)).willReturn(new EffectiveRole("VIEWER", 1));
        given(tokenRepository.countActive(77L, 2L, NOW)).willReturn(4L);
        given(tokenRepository.save(any())).willAnswer(invocation -> {
            WorkspaceAccessToken saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 12L);
            return saved;
        });

        TokenResponse response = service.issue(2L, 77L, new IssueRequest("  Claude Code — 회사 노트북 ", 30));

        assertThat(response.token()).startsWith("cfw_").hasSize(4 + 43);
        ArgumentCaptor<WorkspaceAccessToken> captor = ArgumentCaptor.forClass(WorkspaceAccessToken.class);
        then(tokenRepository).should().save(captor.capture());
        WorkspaceAccessToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).isEqualTo(AccessTokenService.sha256(response.token())).hasSize(64);
        assertThat(saved.getTokenPrefix()).isEqualTo(response.token().substring(0, 12));
        assertThat(saved.getName()).isEqualTo("Claude Code — 회사 노트북");
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusSeconds(30L * 86400));
        assertThat(response.tokenPrefix()).isEqualTo(saved.getTokenPrefix());
        then(auditRecorder).should().record(eq(2L), eq("ACCESS_TOKEN_CREATED"), eq("WORKSPACE"), eq("77"), any());
    }

    @Test
    @DisplayName("발급 — 한 사람이 워크스페이스당 5개까지다")
    void issueLimit() {
        given(roleChecker.requireMember(2L, 77L)).willReturn(new EffectiveRole("EDITOR", 3));
        given(tokenRepository.countActive(77L, 2L, NOW)).willReturn(5L);

        assertThatThrownBy(() -> service.issue(2L, 77L, new IssueRequest("x", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ACCESS_TOKEN_LIMIT_EXCEEDED));
        then(tokenRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("목록 — Owner는 전체를, 그 밖의 멤버는 자기 토큰만 본다. 원문은 나오지 않는다")
    void list() {
        given(tokenRepository.findActiveByWorkspaceId(77L, NOW)).willReturn(List.of(token(1, 2, null), token(2, 5, null)));
        given(userRepository.findAllById(any())).willAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            return java.util.stream.StreamSupport.stream(ids.spliterator(), false).map(id -> user(id, false)).toList();
        });

        given(roleChecker.requireMember(2L, 77L)).willReturn(new EffectiveRole("EDITOR", 3));
        List<TokenResponse> mine = service.list(2L, 77L);
        assertThat(mine).extracting(TokenResponse::tokenId).containsExactly("1");
        assertThat(mine.get(0).token()).isNull();
        assertThat(mine.get(0).createdBy().name()).isEqualTo("사용자2");

        given(roleChecker.requireMember(9L, 77L)).willReturn(new EffectiveRole("OWNER", 4));
        assertThat(service.list(9L, 77L)).extracting(TokenResponse::tokenId).containsExactly("1", "2");
    }

    @Test
    @DisplayName("폐기 — 본인과 Owner만. 남의 토큰은 없는 것처럼 404")
    void revoke() {
        WorkspaceAccessToken others = token(2, 5, null);
        given(tokenRepository.findByIdAndWorkspaceId(2L, 77L)).willReturn(Optional.of(others));

        given(roleChecker.requireMember(2L, 77L)).willReturn(new EffectiveRole("EDITOR", 3));
        assertThatThrownBy(() -> service.revoke(2L, 77L, 2L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ACCESS_TOKEN_NOT_FOUND));
        assertThat(others.getRevokedAt()).isNull();

        given(roleChecker.requireMember(9L, 77L)).willReturn(new EffectiveRole("OWNER", 4));
        service.revoke(9L, 77L, 2L);
        assertThat(others.getRevokedAt()).isEqualTo(NOW);
        then(auditRecorder).should().record(eq(9L), eq("ACCESS_TOKEN_REVOKED"), eq("WORKSPACE"), eq("77"), any());

        // 이미 폐기한 토큰은 다시 폐기할 수 없다
        assertThatThrownBy(() -> service.revoke(9L, 77L, 2L)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("검증 — 유효하면 사용자와 워크스페이스를 돌려주고 마지막 사용 시각을 갱신한다")
    void verifyActive() {
        given(tokenRepository.findByTokenHash("h".repeat(64))).willReturn(Optional.of(token(12, 2, NOW.plusSeconds(60))));
        User active = user(2, false);
        given(userRepository.findById(2L)).willReturn(Optional.of(active));

        VerifyResponse response = service.verify("h".repeat(64));

        assertThat(response.active()).isTrue();
        assertThat(response.userId()).isEqualTo("2");
        assertThat(response.workspaceId()).isEqualTo("77");
        assertThat(response.tokenId()).isEqualTo("12");
        then(tokenRepository).should().touchLastUsed(12L, NOW, NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("검증 — 없는 해시, 폐기, 만료, 탈퇴한 사용자는 모두 active=false이고 사유를 구분하지 않는다")
    void verifyInactive() {
        given(tokenRepository.findByTokenHash("none")).willReturn(Optional.empty());
        assertThat(service.verify("none")).isEqualTo(VerifyResponse.inactive());

        WorkspaceAccessToken revoked = token(1, 2, null);
        revoked.setRevokedAt(NOW.minusSeconds(1));
        given(tokenRepository.findByTokenHash("revoked")).willReturn(Optional.of(revoked));
        assertThat(service.verify("revoked")).isEqualTo(VerifyResponse.inactive());

        given(tokenRepository.findByTokenHash("expired")).willReturn(Optional.of(token(2, 2, NOW)));
        assertThat(service.verify("expired")).isEqualTo(VerifyResponse.inactive());

        given(tokenRepository.findByTokenHash("withdrawn")).willReturn(Optional.of(token(3, 6, null)));
        User withdrawn = user(6, true);
        given(userRepository.findById(6L)).willReturn(Optional.of(withdrawn));
        assertThat(service.verify("withdrawn")).isEqualTo(VerifyResponse.inactive());

        then(tokenRepository).should(never()).touchLastUsed(anyLong(), any(), any());
    }
}
