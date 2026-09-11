package net.java21.crowfoot.common;

/**
 * 공통 응답 포맷의 header — 모든 응답(내부 API 포함)이 항상 포함한다.
 * (01-architecture/api-design.md Section 5)
 *
 * @param isSuccessful 정상 여부
 * @param resultCode   SUCCESS 또는 에러 코드 (Section 5.4 체계)
 * @param resultMessage 성공은 "SUCCESS", 실패는 진단 문구
 */
public record ResponseHeader(boolean isSuccessful, String resultCode, String resultMessage) {

    public static ResponseHeader success() {
        return new ResponseHeader(true, "SUCCESS", "SUCCESS");
    }

    public static ResponseHeader fail(String resultCode, String resultMessage) {
        return new ResponseHeader(false, resultCode, resultMessage);
    }
}
