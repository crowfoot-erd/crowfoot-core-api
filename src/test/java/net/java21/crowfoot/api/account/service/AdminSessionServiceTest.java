package net.java21.crowfoot.api.account.service;

import net.java21.crowfoot.api.account.domain.RefreshToken;
import net.java21.crowfoot.api.account.dto.AdminSessionResponse;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository.SessionRow;
import net.java21.crowfoot.api.client.AuthBlacklistClient;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 관리자 세션 목록·폐기 (08-core/05-account.md Section 2.3~2.4) —
 * sid 단위 집계 규칙과 블랙리스트 등록 → lineage 폐기 → 감사 순서(fail-closed)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final UUID SID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private AdminGuard adminGuard;
    @Mock
    private RefreshTokenQueryRepository refreshTokenQueryRepository;
    @Mock
    private AuthBlacklistClient authBlacklistClient;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private Clock clock;

    @InjectMocks
    private AdminSessionService adminSessionService;

    @Test
    @DisplayName("세션 목록은 sid 단위로 집계한다 — createdAt=최초 발급, ip·UA=최근 발급 행, null은 빈 문자열")
    void sessionsAggregatePerSid() {
        // given: SID 한 줄(lineage) — 3회 발급(rotated 2·active 1), 최근 행에 ip·UA 기록
        Instant first = Instant.parse("2026-09-09T10:00:00Z");
        Instant middle = Instant.parse("2026-09-10T08:00:00Z");
        Instant last = Instant.parse("2026-09-10T08:55:00Z");
        RefreshToken t1 = token(SID, first, null, null);
        RefreshToken t2 = token(SID, middle, middle, null); // lastUsedAt 기록
        RefreshToken t3 = token(SID, last, null, "203.0.113.10"); // 최근 발급 행 — ip·UA 원천
        given(refreshTokenQueryRepository.countActiveSessions(3L)).willReturn(1L);
        given(refreshTokenQueryRepository.findActiveSessionPage(3L, 0L, 20))
                .willReturn(List.of(new SessionRow(SID, first)));
        given(refreshTokenQueryRepository.findActiveRowsBySessionIds(List.of(SID)))
                .willReturn(List.of(t1, t2, t3)); // issued_at asc

        // when
        ListApiResponse<AdminSessionResponse> result = adminSessionService.sessions(2L, "3", 1, 20);

        // then
        assertThat(result.totalCount()).isEqualTo(1);
        AdminSessionResponse session = result.responses().get(0);
        assertThat(session.sid()).isEqualTo(SID.toString());
        assertThat(session.createdAt()).isEqualTo(first);          // MIN(issued_at)
        assertThat(session.lastUsedAt()).isEqualTo(middle);        // MAX(last_used_at)
        assertThat(session.ip()).isEqualTo("203.0.113.10");        // 최근 발급 행
        assertThat(session.userAgent()).isEqualTo("Mozilla/5.0");  // 최근 발급 행
    }

    @Test
    @DisplayName("lastUsedAt 기록이 없으면 createdAt으로 대체하고, ip·UA 미기록은 빈 문자열로 응답한다")
    void sessionsFallbackWhenOptionalColumnsAbsent() {
        // given
        Instant first = Instant.parse("2026-09-09T10:00:00Z");
        RefreshToken only = token(SID, first, null, null); // ip·UA·lastUsedAt 전부 없음
        given(refreshTokenQueryRepository.countActiveSessions(3L)).willReturn(1L);
        given(refreshTokenQueryRepository.findActiveSessionPage(3L, 0L, 20))
                .willReturn(List.of(new SessionRow(SID, first)));
        given(refreshTokenQueryRepository.findActiveRowsBySessionIds(List.of(SID)))
                .willReturn(List.of(only));

        // when
        AdminSessionResponse session = adminSessionService.sessions(2L, "3", 1, 20).responses().get(0);

        // then
        assertThat(session.lastUsedAt()).isEqualTo(first);
        assertThat(session.ip()).isEmpty();
        assertThat(session.userAgent()).isEmpty();
    }

    @Test
    @DisplayName("활성 세션이 없으면 빈 페이지 — 대상 사용자 존재 여부도 노출하지 않는다")
    void sessionsReturnsEmptyPageWhenNone() {
        // given
        given(refreshTokenQueryRepository.countActiveSessions(99L)).willReturn(0L);

        // when
        ListApiResponse<AdminSessionResponse> result = adminSessionService.sessions(2L, "99", 1, 20);

        // then
        assertThat(result.totalCount()).isZero();
        assertThat(result.responses()).isEmpty();
        verify(refreshTokenQueryRepository, never()).findActiveSessionPage(any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("userId 누락·비숫자는 400 INVALID_REQUEST")
    void sessionsRejectsInvalidUserId() {
        // when & then
        assertThatThrownBy(() -> adminSessionService.sessions(2L, null, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> adminSessionService.sessions(2L, "abc", 1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("폐기는 블랙리스트 등록 후 lineage 폐기·감사를 수행한다(등록 성공 후 커밋)")
    void revokeRegistersBlacklistThenRevokesAndAudits() {
        // given
        given(clock.instant()).willReturn(NOW);
        given(refreshTokenQueryRepository.existsActiveSession(SID)).willReturn(true);
        given(refreshTokenQueryRepository.revokeBySessionId(SID, NOW)).willReturn(3L);

        // when
        adminSessionService.revokeSession(2L, SID.toString());

        // then
        InOrder order = inOrder(authBlacklistClient, refreshTokenQueryRepository, auditRecorder);
        order.verify(authBlacklistClient).registerSessionBlacklist(SID.toString());
        order.verify(refreshTokenQueryRepository).revokeBySessionId(SID, NOW);
        order.verify(auditRecorder).record(2L, "SESSION_REVOKED_BY_ADMIN", "SESSION", SID.toString(),
                java.util.Map.of("revokedTokens", 3L));
    }

    @Test
    @DisplayName("이미 종료되었거나 없는 sid는 404 SESSION_NOT_FOUND — 블랙리스트 미호출")
    void revokeRejectsAlreadyTerminatedSession() {
        // given
        given(refreshTokenQueryRepository.existsActiveSession(SID)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> adminSessionService.revokeSession(2L, SID.toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SESSION_NOT_FOUND));
        verify(authBlacklistClient, never()).registerSessionBlacklist(any());
        verify(refreshTokenQueryRepository, never()).revokeBySessionId(any(), any());
    }

    @Test
    @DisplayName("블랙리스트 등록 실패 시 lineage 폐기를 수행하지 않는다(fail-closed)")
    void revokeFailsClosedWhenBlacklistRejected() {
        // given
        given(refreshTokenQueryRepository.existsActiveSession(SID)).willReturn(true);
        willThrow(new RuntimeException("auth unavailable"))
                .given(authBlacklistClient).registerSessionBlacklist(SID.toString());

        // when & then
        assertThatThrownBy(() -> adminSessionService.revokeSession(2L, SID.toString()))
                .isInstanceOf(RuntimeException.class);
        verify(refreshTokenQueryRepository, never()).revokeBySessionId(any(), any());
    }

    @Test
    @DisplayName("sid가 UUID 형식이 아니면 400 INVALID_REQUEST")
    void revokeRejectsNonUuidSid() {
        // when & then
        assertThatThrownBy(() -> adminSessionService.revokeSession(2L, "not-a-uuid"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private RefreshToken token(UUID sid, Instant issuedAt, Instant lastUsedAt, String ip) {
        return new RefreshToken(UUID.randomUUID(), sid, 3L, "hash-" + issuedAt,
                issuedAt, lastUsedAt, issuedAt.plusSeconds(3600),
                ip, ip == null ? null : "Mozilla/5.0");
    }
}
