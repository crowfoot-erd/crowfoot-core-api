package net.java21.crowfoot.api.client;

/**
 * 인증 서버 Access 블랙리스트 등록 (02-auth/api.md Section 4.2 — core → auth 내부 호출).
 *
 * <p>폐기 세션의 Access 잔여 수명을 기다리지 않고 즉시 차단하기 위해
 * 관리자 세션 폐기·회원 탈퇴에서 Refresh lineage 폐기와 세트로 호출한다.
 */
public interface AuthBlacklistClient {

    /** sid 1건을 블랙리스트에 등록한다 — 실패하면 예외를 던진다(fail-closed: 등록 성공 후 커밋). */
    void registerSessionBlacklist(String sid);
}
