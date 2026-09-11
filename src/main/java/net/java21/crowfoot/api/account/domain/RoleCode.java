package net.java21.crowfoot.api.account.domain;

/**
 * 역할 코드 상수 — roles 테이블 시드(OWNER=40/EDITOR=30/COMMENTER=20/VIEWER=10)와 대응.
 * 권한 서열 비교는 테이블의 level을 기준으로 하며, 이 enum은 코드 값의 타입 안전성용이다.
 */
public enum RoleCode {
    OWNER,
    EDITOR,
    COMMENTER,
    VIEWER;

    /** 멤버십 부여·역할 변경으로 지정 가능한 역할 — OWNER(소유권 이전)는 1단계 미제공 */
    public static boolean isAssignable(String code) {
        return EDITOR.name().equals(code) || COMMENTER.name().equals(code) || VIEWER.name().equals(code);
    }
}
