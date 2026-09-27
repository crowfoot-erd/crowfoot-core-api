package net.java21.crowfoot.api.auth;

/**
 * 요청 스코프 사용자 컨텍스트 — XUserIdFilter가 설정/해제한다.
 */
public final class CurrentUserHolder {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new IllegalStateException("인증 컨텍스트가 없습니다 — XUserIdFilter를 거치지 않은 경로입니다");
        }
        return user;
    }

    /** 선택 인증 경로(공유 댓글 — 02-model.md §1.10.7)용 — 없으면 null(비회원 요청) */
    public static CurrentUser getOrNull() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
