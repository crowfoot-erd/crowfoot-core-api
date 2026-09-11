package net.java21.crowfoot.api.internal.service;

import net.java21.crowfoot.api.account.domain.RefreshToken;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository;
import net.java21.crowfoot.api.account.repository.RefreshTokenRepository;
import net.java21.crowfoot.api.account.service.SessionRevoker;
import net.java21.crowfoot.api.config.AppProperties;
import net.java21.crowfoot.api.internal.dto.RegisterRefreshTokenRequest;
import net.java21.crowfoot.api.internal.dto.RotateRefreshTokenRequest;
import net.java21.crowfoot.api.internal.dto.RotateRefreshTokenResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh 저장소 내부 API (08-core/05-account.md Section 3.2~3.4).
 *
 * <p>Rotation 3-조건 판정(02-auth/requirements.md Section 1.4.4 메커니즘):
 * 정상(ROTATED) / 유예 30초 내 재사용(GRACE — 멀티탭 동시 재발급) / 유예 초과·폐기 토큰 재사용
 * (세션 전체 무효화 후 409 AUTH_SESSION_REVOKED).
 */
@Service
public class InternalRefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenQueryRepository refreshTokenQueryRepository;
    private final SessionRevoker sessionRevoker;
    private final Clock clock;
    private final Duration gracePeriod;

    public InternalRefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                                       RefreshTokenQueryRepository refreshTokenQueryRepository,
                                       SessionRevoker sessionRevoker,
                                       Clock clock,
                                       AppProperties appProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenQueryRepository = refreshTokenQueryRepository;
        this.sessionRevoker = sessionRevoker;
        this.clock = clock;
        this.gracePeriod = Duration.ofSeconds(appProperties.rotation().graceSeconds());
    }

    /** Refresh 등록(발급) — 발급마다 신규 행 INSERT */
    @Transactional
    public void register(RegisterRefreshTokenRequest request) {
        if (refreshTokenRepository.existsById(UUID.fromString(request.jti()))) {
            return; // 이미 등록된 jti — 재시도 멱등 처리
        }
        long userId = Long.parseLong(request.userId());
        UUID jti = UUID.fromString(request.jti());
        Instant now = clock.instant();
        refreshTokenRepository.save(new RefreshToken(
                jti, UUID.fromString(request.sid()), userId, sha256Hex(jti),
                now, now, request.expiresAt(), request.ip(), request.userAgent()));
    }

    /**
     * Rotation 판정·교체 — 유예 초과·폐기 토큰 재사용은 세션 lineage 전량 폐기(별도 트랜잭션 커밋) 후
     * 409 AUTH_SESSION_REVOKED로 응답한다.
     */
    @Transactional
    public RotateRefreshTokenResponse rotate(RotateRefreshTokenRequest request) {
        RefreshToken current = refreshTokenRepository.findByJti(UUID.fromString(request.currentJti()))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
        long userId = Long.parseLong(request.userId());
        if (current.getUserId() != userId) {
            // 세션 소유자 불일치 — 존재하지 않는 것과 같이 처리
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        Instant now = clock.instant();
        if (current.isRevoked()) {
            return reuseDetected(current, now);
        }
        if (current.getRotatedAt() != null) {
            boolean withinGrace = Duration.between(current.getRotatedAt(), now).compareTo(gracePeriod) <= 0;
            if (!withinGrace) {
                return reuseDetected(current, now);
            }
            // GRACE — 유예 내 구 Refresh 재사용은 최신 토큰으로 이어서 Rotation(멀티탭)
            RefreshToken latest = refreshTokenQueryRepository
                    .findLatestActive(current.getSessionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
            latest.setLastUsedAt(now);
            return new RotateRefreshTokenResponse("GRACE", latest.getJti().toString());
        }

        // ROTATED — 구 jti 폐기 + 새 행 등록(슬라이딩 만료 승계)
        current.setRotatedAt(now);
        current.setLastUsedAt(now);
        UUID newJti = UUID.fromString(request.newJti());
        refreshTokenRepository.save(new RefreshToken(
                newJti, current.getSessionId(), userId, sha256Hex(newJti),
                now, now, request.newExpiresAt(), current.getIp(), current.getUserAgent()));
        return new RotateRefreshTokenResponse("ROTATED", newJti.toString());
    }

    /** Refresh 1건 폐기(로그아웃) — 멱등: 폐기할 행이 없어도 성공 */
    @Transactional
    public void revokeJti(String jti) {
        refreshTokenRepository.findByJti(UUID.fromString(jti)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(clock.instant());
            }
        });
    }

    /** 세션(Refresh lineage) 전량 폐기 — 재사용 감지·세션 무효화. 멱등 */
    @Transactional
    public void revokeSession(String sid) {
        refreshTokenQueryRepository.revokeBySessionId(UUID.fromString(sid), clock.instant());
    }

    private RotateRefreshTokenResponse reuseDetected(RefreshToken current, Instant now) {
        // 세션 전체 무효화 — 폐기는 별도 트랜잭션에서 커밋되므로 409 전파에도 롤백되지 않는다
        sessionRevoker.revokeSession(current.getSessionId(), current.getUserId(),
                current.isRevoked() ? "revoked-token-reuse" : "grace-exceeded-reuse");
        throw new BusinessException(ErrorCode.AUTH_SESSION_REVOKED);
    }

    private static String sha256Hex(UUID value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 미지원 환경", ex);
        }
    }
}
