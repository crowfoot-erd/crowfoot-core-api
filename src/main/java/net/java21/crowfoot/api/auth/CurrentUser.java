package net.java21.crowfoot.api.auth;

/**
 * 인증된 요청 사용자 — Gateway가 Introspection 검증 후 주입한 X-USER-ID(JWT sub 문자열) 원천.
 * (08-core/00-overview.md Section 1 — core는 인증 코드를 갖지 않고 이 헤더를 신뢰한다)
 *
 * <p>워크스페이스 액세스 토큰으로 온 요청(MCP)은 토큰이 묶인 워크스페이스와 토큰 ID를 함께 갖는다
 * (08-core/18-access-token.md Section 4). 웹 요청에서는 둘 다 null이다.
 *
 * @param tokenWorkspaceId 토큰이 묶인 워크스페이스(X-TOKEN-WORKSPACE-ID) — 토큰으로 온 요청에만 있다
 * @param tokenId          토큰 ID(X-ACCESS-TOKEN-ID) — 감사 기록의 detail에 남긴다
 */
public record CurrentUser(long userId, Long tokenWorkspaceId, Long tokenId) {

    public CurrentUser(long userId) {
        this(userId, null, null);
    }

    public String userIdString() {
        return Long.toString(userId);
    }

    /** 워크스페이스 액세스 토큰으로 온 요청인가 */
    public boolean viaToken() {
        return tokenWorkspaceId != null;
    }
}
