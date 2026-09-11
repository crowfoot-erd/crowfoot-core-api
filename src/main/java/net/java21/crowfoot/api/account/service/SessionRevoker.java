package net.java21.crowfoot.api.account.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 세션(Refresh lineage) 폐기 — 별도 트랜잭션(REQUIRES_NEW)으로 커밋을 보장한다.
 *
 * <p>재사용 감지(rotate) 경로는 "lineage 전량 폐기 + 감사 기록 후 409"인데,
 * 폐기를 호출자의 트랜잭션에서 수행하면 409 예외 전파 때 함께 롤백된다 — 그래서 분리했다.
 */
@Component
@RequiredArgsConstructor
public class SessionRevoker {

    private final RefreshTokenQueryRepository refreshTokenQueryRepository;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeSession(UUID sessionId, Long actorUserId, String reason) {
        Instant now = clock.instant();
        long revoked = refreshTokenQueryRepository.revokeBySessionId(sessionId, now);
        auditRecorder.record(actorUserId, "REFRESH_REUSED", "SESSION", sessionId.toString(),
                reason == null ? null : Map.of("reason", reason, "revokedTokens", revoked));
    }
}
