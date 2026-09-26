package net.java21.crowfoot.api.internal.dto;

/**
 * 협업 서버용 사용자 프로필 응답 (08-core internal — 05-editor/03-collaboration.md Section 2.1).
 * introspection이 신원(sub)만 주므로 표시명·아바타·GitHub 핸들은 이 응답으로 내려준다
 * (avatarUrl 도출·핸들 저장 규칙은 /me와 동일 — 08-core/05-account.md Section 1.1).
 */
public record CollabProfileResponse(String userId, String name, String avatarUrl, String githubLogin) {

    public static CollabProfileResponse of(long userId, String name, String avatarUrl, String githubLogin) {
        return new CollabProfileResponse(Long.toString(userId), name, avatarUrl, githubLogin);
    }
}
