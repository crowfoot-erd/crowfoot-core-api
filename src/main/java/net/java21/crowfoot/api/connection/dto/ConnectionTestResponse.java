package net.java21.crowfoot.api.connection.dto;

/**
 * 접속 테스트 결과 (08-core/06-connection.md Section 3.5) — 실패도 계약 응답이다(200 + connected:false).
 *
 * @param connected 접속·SELECT 1 성공 여부
 * @param latencyMs 접속 소요 시간 — 성공 시에만 의미가 있다
 * @param message   실패 원인 분류 문구(예외 원문 노출 금지)
 */
public record ConnectionTestResponse(boolean connected, long latencyMs, String message) {

    public static ConnectionTestResponse ok(long latencyMs) {
        return new ConnectionTestResponse(true, latencyMs, null);
    }

    public static ConnectionTestResponse fail(String message) {
        return new ConnectionTestResponse(false, 0, message);
    }
}
