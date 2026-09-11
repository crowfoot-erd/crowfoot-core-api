package net.java21.crowfoot.api.account.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.RefreshToken;
import net.java21.crowfoot.api.account.dto.AdminPageParams;
import net.java21.crowfoot.api.account.dto.AdminSessionResponse;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository.SessionRow;
import net.java21.crowfoot.api.client.AuthBlacklistClient;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 관리자 — 활성 세션 목록·개별 폐기 (08-core/05-account.md Section 2.3~2.4).
 *
 * <p>세션 = 활성 lineage(revoked_at IS NULL 행)가 남은 sid 단위. 폐기는
 * 인증 서버 블랙리스트 등록 성공 후 커밋한다(fail-closed — 회원 탈퇴와 같은 순서)며,
 * 이미 종료된 세션은 404 SESSION_NOT_FOUND로 알린다(내부 폐기 API의 멱등 204와 다른 점).
 */
@Service
@RequiredArgsConstructor
public class AdminSessionService {

    private final AdminGuard adminGuard;
    private final RefreshTokenQueryRepository refreshTokenQueryRepository;
    private final AuthBlacklistClient authBlacklistClient;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    /** 사용자 활성 세션 목록 — 최근 로그인 순. 대상 사용자가 없어도 빈 페이지(존재 미노출) */
    @Transactional(readOnly = true)
    public ListApiResponse<AdminSessionResponse> sessions(long adminId, String userId, Integer page, Integer size) {
        adminGuard.requireAdmin(adminId);
        long targetId = requireNumericUserId(userId);
        AdminPageParams params = AdminPageParams.of(page, size);

        long total = refreshTokenQueryRepository.countActiveSessions(targetId);
        List<AdminSessionResponse> responses;
        if (total == 0) {
            responses = List.of();
        } else {
            List<SessionRow> pageRows = refreshTokenQueryRepository
                    .findActiveSessionPage(targetId, params.offset(), params.size());
            responses = aggregate(refreshTokenQueryRepository.findActiveRowsBySessionIds(
                    pageRows.stream().map(SessionRow::sessionId).toList()), pageRows);
        }

        auditRecorder.record(adminId, "ADMIN_SESSIONS_LISTED", "USER", Long.toString(targetId),
                Map.of("totalCount", total));
        return ListApiResponse.paged(responses, params.page(), params.size(), total);
    }

    /** 세션 폐기 — 활성 검사 → 블랙리스트 등록 → lineage 폐기 → 감사(성공 후 커밋) */
    @Transactional
    public void revokeSession(long adminId, String sid) {
        adminGuard.requireAdmin(adminId);
        UUID sessionId = parseSid(sid);
        if (!refreshTokenQueryRepository.existsActiveSession(sessionId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "이미 종료되었거나 없는 세션입니다");
        }
        // 등록 성공 후 커밋(fail-closed) — 실패 시 SERVICE_UNAVAILABLE으로 롤백
        authBlacklistClient.registerSessionBlacklist(sid);
        long revoked = refreshTokenQueryRepository.revokeBySessionId(sessionId, clock.instant());
        auditRecorder.record(adminId, "SESSION_REVOKED_BY_ADMIN", "SESSION", sessionId.toString(),
                Map.of("revokedTokens", revoked));
    }

    /** sid별 집계 — createdAt=최초 발급, lastUsedAt=마지막 사용(없으면 최초 발급), ip·UA=최근 발급 행 */
    private static List<AdminSessionResponse> aggregate(List<RefreshToken> activeRows, List<SessionRow> pageRows) {
        Map<UUID, List<RefreshToken>> bySession = activeRows.stream()
                .collect(Collectors.groupingBy(RefreshToken::getSessionId));
        return pageRows.stream()
                .map(row -> {
                    // findActiveRowsBySessionIds가 issued_at asc로 정렬을 보장 — first=최초, last=최근
                    List<RefreshToken> lineage = bySession.getOrDefault(row.sessionId(), List.of());
                    Instant createdAt = lineage.isEmpty() ? row.firstIssuedAt() : lineage.get(0).getIssuedAt();
                    Instant lastUsedAt = lineage.stream()
                            .map(RefreshToken::getLastUsedAt)
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(createdAt);
                    RefreshToken latest = lineage.isEmpty() ? null : lineage.get(lineage.size() - 1);
                    return new AdminSessionResponse(row.sessionId().toString(), createdAt, lastUsedAt,
                            latest == null || latest.getIp() == null ? "" : latest.getIp(),
                            latest == null || latest.getUserAgent() == null ? "" : latest.getUserAgent());
                })
                .toList();
    }

    private static long requireNumericUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "userId는 필수입니다");
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "userId가 숫자가 아닙니다");
        }
    }

    private static UUID parseSid(String sid) {
        try {
            return UUID.fromString(sid);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sid 형식이 올바르지 않습니다");
        }
    }
}
