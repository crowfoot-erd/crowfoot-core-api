package net.java21.crowfoot.api.auth;

/**
 * 인증된 요청 사용자 — Gateway가 Introspection 검증 후 주입한 X-USER-ID(JWT sub 문자열) 원천.
 * (08-core/00-overview.md Section 1 — core는 인증 코드를 갖지 않고 이 헤더를 신뢰한다)
 */
public record CurrentUser(long userId) {

    public String userIdString() {
        return Long.toString(userId);
    }
}
