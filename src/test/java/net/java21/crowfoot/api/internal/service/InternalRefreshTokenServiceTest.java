package net.java21.crowfoot.api.internal.service;

import net.java21.crowfoot.api.account.domain.RefreshToken;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository;
import net.java21.crowfoot.api.account.repository.RefreshTokenRepository;
import net.java21.crowfoot.api.account.service.SessionRevoker;
import net.java21.crowfoot.api.config.AppProperties;
import net.java21.crowfoot.api.internal.dto.RotateRefreshTokenRequest;
import net.java21.crowfoot.api.internal.dto.RotateRefreshTokenResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refresh Rotation 3-조건 판정 (02-auth/requirements.md Section 1.4.4) — Clock 고정.
 * ROTATED(정상) / GRACE(유예 30초 내 재사용) / 세션 폐기 + 409(유예 초과·폐기 토큰 재사용).
 */
@ExtendWith(MockitoExtension.class)
class InternalRefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:30Z");
    private static final UUID CURRENT_JTI = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEW_JTI = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final long USER_ID = 1L;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private RefreshTokenQueryRepository refreshTokenQueryRepository;
    @Mock
    private SessionRevoker sessionRevoker;

    private InternalRefreshTokenService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new InternalRefreshTokenService(refreshTokenRepository, refreshTokenQueryRepository,
                sessionRevoker, fixed, new AppProperties(new AppProperties.Rotation(30),
                new AppProperties.Auth("http://localhost:8081")));
    }

    @Test
    @DisplayName("정상 재발급 — ROTATED 판정, 구 토큰 rotatedAt 폐기, 새 행 등록")
    void normalRotationIsRotated() {
        // given
        RefreshToken current = token(null, null);
        when(refreshTokenRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(current));

        // when
        RotateRefreshTokenResponse response = rotate();

        // then
        assertThat(response.verdict()).isEqualTo("ROTATED");
        assertThat(response.latestJti()).isEqualTo(NEW_JTI.toString());
        assertThat(current.getRotatedAt()).isEqualTo(NOW);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().getJti()).isEqualTo(NEW_JTI);
        assertThat(saved.getValue().getSessionId()).isEqualTo(SID);
        verify(sessionRevoker, never()).revokeSession(any(UUID.class), anyLong(), any());
    }

    @Test
    @DisplayName("유예(30초) 내 구 토큰 재사용 — GRACE로 최신 토큰 jti를 돌려준다")
    void reuseWithinGraceIsGrace() {
        // given
        RefreshToken current = token(NOW.minus(Duration.ofSeconds(10)), null);
        RefreshToken latest = token(null, null);
        latest.setJti(NEW_JTI);
        when(refreshTokenRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(current));
        when(refreshTokenQueryRepository.findLatestActive(SID)).thenReturn(Optional.of(latest));

        // when
        RotateRefreshTokenResponse response = rotate();

        // then
        assertThat(response.verdict()).isEqualTo("GRACE");
        assertThat(response.latestJti()).isEqualTo(NEW_JTI.toString());
        verify(sessionRevoker, never()).revokeSession(any(UUID.class), anyLong(), any());
    }

    @Test
    @DisplayName("유예 초과 재사용 — 세션 lineage 폐기 후 409 AUTH_SESSION_REVOKED")
    void reuseAfterGraceRevokesSession() {
        // given
        RefreshToken current = token(NOW.minus(Duration.ofSeconds(31)), null);
        when(refreshTokenRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(current));

        // when & then
        assertThatThrownBy(this::rotate)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTH_SESSION_REVOKED));
        verify(sessionRevoker).revokeSession(SID, USER_ID, "grace-exceeded-reuse");
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("폐기 토큰 재사용 — 즉시 세션 폐기 후 409")
    void revokedTokenReuseRevokesSession() {
        // given
        RefreshToken current = token(null, NOW.minus(Duration.ofMinutes(5)));
        when(refreshTokenRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(current));

        // when & then
        assertThatThrownBy(this::rotate)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTH_SESSION_REVOKED));
        verify(sessionRevoker).revokeSession(SID, USER_ID, "revoked-token-reuse");
    }

    @Test
    @DisplayName("알 수 없는 jti — 404 REFRESH_TOKEN_NOT_FOUND")
    void unknownJtiIs404() {
        // given
        when(refreshTokenRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(this::rotate)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    @Test
    @DisplayName("세션 소유자 불일치 — 존재하지 않는 것과 같이 404")
    void ownerMismatchIs404() {
        // given
        RefreshToken otherUsers = new RefreshToken(CURRENT_JTI, SID, 999L, "hash",
                NOW, NOW, NOW.plus(Duration.ofDays(14)), "127.0.0.1", "UA");
        when(refreshTokenRepository.findByJti(CURRENT_JTI)).thenReturn(Optional.of(otherUsers));

        // when & then
        assertThatThrownBy(this::rotate)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    private RotateRefreshTokenResponse rotate() {
        return service.rotate(new RotateRefreshTokenRequest(
                Long.toString(USER_ID), CURRENT_JTI.toString(), NEW_JTI.toString(),
                NOW.plus(Duration.ofDays(14))));
    }

    private RefreshToken token(Instant rotatedAt, Instant revokedAt) {
        RefreshToken token = new RefreshToken(CURRENT_JTI, SID, USER_ID, "hash",
                NOW.minus(Duration.ofMinutes(5)), NOW.minus(Duration.ofMinutes(5)),
                NOW.plus(Duration.ofDays(14)), "127.0.0.1", "UA");
        token.setRotatedAt(rotatedAt);
        token.setRevokedAt(revokedAt);
        return token;
    }
}
