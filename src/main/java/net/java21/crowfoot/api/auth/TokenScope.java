package net.java21.crowfoot.api.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 워크스페이스 액세스 토큰으로 온 요청의 범위 판정 (08-core/18-access-token.md Section 4).
 * 토큰은 묶인 워크스페이스 밖으로 나가지 못하고, 그 안에서도 사람이 화면에서 해야 하는 일은 막는다.
 * MCP 서버(crowfoot-mcp)가 처음부터 헤더의 워크스페이스로만 부르지만, 그쪽에 버그가 있어도 범위가 지켜지게 하는 두 번째 방어선이다.
 */
public final class TokenScope {

    public enum Decision {
        ALLOW,
        /** 다른 워크스페이스 — 없는 것처럼 숨긴다(404 WORKSPACE_NOT_FOUND) */
        NOT_FOUND,
        /** 토큰으로 부를 수 없는 경로(403 PERMISSION_DENIED) */
        FORBIDDEN
    }

    private static final Pattern WORKSPACE = Pattern.compile("^/core/workspaces/(\\d+)(/.*)?$");
    private static final Pattern MODEL = Pattern.compile("^/models/\\d+$");
    private static final Pattern CONNECTION = Pattern.compile("^/connections(/\\d+)?$");
    private static final Pattern MANAGED = Pattern.compile("^/managed-databases/\\d+(/credential)?$");
    private static final Pattern SHARES = Pattern.compile("^/models/\\d+/shares(/.*)?$");

    private TokenScope() {
    }

    public static Decision decide(String method, String path, long tokenWorkspaceId) {
        if ("GET".equals(method) && "/core/database-types".equals(path)) {
            return Decision.ALLOW;
        }
        Matcher matcher = WORKSPACE.matcher(path);
        if (!matcher.matches()) {
            // 계정, 팀, 관리자, 커뮤니티, 알림 등 워크스페이스 밖의 경로
            return Decision.FORBIDDEN;
        }
        if (!matcher.group(1).equals(Long.toString(tokenWorkspaceId))) {
            return Decision.NOT_FOUND;
        }
        String rest = matcher.group(2) == null ? "" : matcher.group(2);
        boolean read = "GET".equals(method);
        // 토큰이 토큰을 만들지 못하게 한다
        if (rest.startsWith("/access-tokens")) {
            return Decision.FORBIDDEN;
        }
        // 워크스페이스 설정 변경·삭제, 멤버십 부여·변경·회수 — 사람이 화면에서 한다
        if (rest.isEmpty() && !read) {
            return Decision.FORBIDDEN;
        }
        if (rest.startsWith("/memberships") && !read) {
            return Decision.FORBIDDEN;
        }
        // 문서 삭제
        if ("DELETE".equals(method) && MODEL.matcher(rest).matches()) {
            return Decision.FORBIDDEN;
        }
        // 커넥션 등록·변경·삭제 — 접속 정보와 "MCP 반영 허용" 설정은 사람이 다룬다
        if (!read && CONNECTION.matcher(rest).matches()) {
            return Decision.FORBIDDEN;
        }
        // 매니지드 데이터베이스 철회(데이터 삭제)와 접속 정보 조회(비밀번호)
        Matcher managed = MANAGED.matcher(rest);
        if (managed.matches() && ("DELETE".equals(method) || managed.group(1) != null)) {
            return Decision.FORBIDDEN;
        }
        // 공유 링크 발급·철회 — 공개 범위는 사람이 정한다
        if (!read && SHARES.matcher(rest).matches()) {
            return Decision.FORBIDDEN;
        }
        return Decision.ALLOW;
    }
}
