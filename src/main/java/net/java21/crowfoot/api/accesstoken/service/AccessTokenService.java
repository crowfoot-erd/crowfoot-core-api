package net.java21.crowfoot.api.accesstoken.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.accesstoken.domain.WorkspaceAccessToken;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.IssueRequest;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.TokenResponse;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.VerifyResponse;
import net.java21.crowfoot.api.accesstoken.repository.WorkspaceAccessTokenRepository;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크스페이스 액세스 토큰 (08-core/18-access-token.md) — MCP 클라이언트용 장기 자격의 발급·목록·폐기와 검증.
 * 토큰으로 온 요청은 발급한 사용자의 요청이다. 권한은 그 워크스페이스에서 그 사용자가 가진 역할이고 요청마다 판정한다 —
 * 여기서는 토큰이 살아 있는지만 본다.
 */
@Service
@RequiredArgsConstructor
public class AccessTokenService {

    public static final String PREFIX = "cfw_";
    private static final int PREFIX_LENGTH = 12;
    private static final int LIMIT_PER_USER = 5;
    private static final Duration LAST_USED_INTERVAL = Duration.ofMinutes(1);
    private static final String OWNER = "OWNER";

    private final WorkspaceAccessTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** 목록 — Owner는 워크스페이스의 모든 토큰, 그 밖의 멤버는 자기 토큰만 (3.1) */
    @Transactional(readOnly = true)
    public List<TokenResponse> list(long userId, long workspaceId) {
        EffectiveRole role = roleChecker.requireMember(userId, workspaceId);
        boolean owner = OWNER.equals(role.code());
        List<WorkspaceAccessToken> tokens = tokenRepository.findActiveByWorkspaceId(workspaceId, clock.instant()).stream()
                .filter(token -> owner || token.getUserId() == userId)
                .toList();
        // 발급한 사용자의 이름 — IN 한 번으로 읽는다(N+1 방지)
        Map<Long, String> names = new HashMap<>();
        userRepository.findAllById(tokens.stream().map(WorkspaceAccessToken::getUserId).distinct().toList())
                .forEach(user -> names.put(user.getId(), user.getName()));
        return tokens.stream().map(token -> toResponse(token, names.get(token.getUserId()), null)).toList();
    }

    /** 발급 — 멤버 누구나. 원문은 이 응답에서만 나온다 (3.2) */
    @Transactional
    public TokenResponse issue(long userId, long workspaceId, IssueRequest request) {
        roleChecker.requireMember(userId, workspaceId);
        Instant now = clock.instant();
        if (tokenRepository.countActive(workspaceId, userId, now) >= LIMIT_PER_USER) {
            throw new BusinessException(ErrorCode.ACCESS_TOKEN_LIMIT_EXCEEDED);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = request.expiresInDays() == null ? null : now.plus(Duration.ofDays(request.expiresInDays()));
        WorkspaceAccessToken token = tokenRepository.save(new WorkspaceAccessToken(workspaceId, userId, request.name().strip(),
                raw.substring(0, PREFIX_LENGTH), sha256(raw), expiresAt, now));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tokenId", String.valueOf(token.getId()));
        detail.put("name", token.getName());
        auditRecorder.record(userId, "ACCESS_TOKEN_CREATED", "WORKSPACE", Long.toString(workspaceId), detail);
        String name = userRepository.findById(userId).map(User::getName).orElse(null);
        return toResponse(token, name, raw);
    }

    /** 폐기 — 발급한 본인 또는 Owner. 남의 토큰을 Owner가 아닌 사람이 지정하면 없는 것처럼 404 (3.3) */
    @Transactional
    public void revoke(long userId, long workspaceId, long tokenId) {
        EffectiveRole role = roleChecker.requireMember(userId, workspaceId);
        WorkspaceAccessToken token = tokenRepository.findByIdAndWorkspaceId(tokenId, workspaceId)
                .filter(found -> found.getRevokedAt() == null)
                .filter(found -> found.getUserId() == userId || OWNER.equals(role.code()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_TOKEN_NOT_FOUND));
        token.setRevokedAt(clock.instant());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tokenId", String.valueOf(token.getId()));
        detail.put("name", token.getName());
        auditRecorder.record(userId, "ACCESS_TOKEN_REVOKED", "WORKSPACE", Long.toString(workspaceId), detail);
    }

    /**
     * 검증 — 인증 서버가 Introspection 중에 부른다 (3.4).
     * 해시가 없거나, 폐기했거나, 만료됐거나, 발급한 사용자가 탈퇴했으면 active=false다. 멤버십은 여기서 보지 않는다.
     */
    @Transactional
    public VerifyResponse verify(String tokenHash) {
        Instant now = clock.instant();
        WorkspaceAccessToken token = tokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (token == null || !token.isActive(now)) {
            return VerifyResponse.inactive();
        }
        boolean withdrawn = userRepository.findById(token.getUserId()).map(User::isWithdrawn).orElse(true);
        if (withdrawn) {
            return VerifyResponse.inactive();
        }
        VerifyResponse response = new VerifyResponse(true, String.valueOf(token.getUserId()), String.valueOf(token.getWorkspaceId()),
                String.valueOf(token.getId()), token.getExpiresAt());
        tokenRepository.touchLastUsed(token.getId(), now, now.minus(LAST_USED_INTERVAL));
        return response;
    }

    private static TokenResponse toResponse(WorkspaceAccessToken token, String userName, String raw) {
        return new TokenResponse(String.valueOf(token.getId()), token.getName(), token.getTokenPrefix(),
                new UserRefResponse(String.valueOf(token.getUserId()), userName), token.getExpiresAt(), token.getLastUsedAt(),
                token.getCreatedAt(), raw);
    }

    /** 토큰 원문의 SHA-256(16진수 64자) */
    public static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
