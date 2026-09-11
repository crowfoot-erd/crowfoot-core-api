package net.java21.crowfoot.api.internal.dto;

/**
 * 사용자 확보 응답 — created로 신규 여부, admin을 토큰 클레임용으로 전달한다.
 */
public record GetOrCreateUserResponse(String userId, boolean created, boolean admin) {

    public static GetOrCreateUserResponse of(long userId, boolean created, boolean admin) {
        return new GetOrCreateUserResponse(Long.toString(userId), created, admin);
    }
}
