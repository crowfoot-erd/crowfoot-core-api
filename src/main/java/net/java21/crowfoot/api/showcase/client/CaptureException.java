package net.java21.crowfoot.api.showcase.client;

/**
 * 캡처 실패 — code는 캡처 서비스의 resultCode(INVALID_URL·BLOCKED_ADDRESS·CAPTURE_FAILED·CAPTURE_BUSY)이거나,
 * 서비스에 닿지 못했으면 CAPTURE_UNAVAILABLE이다. message는 사용자에게 보여 줄 한 줄 사유다.
 */
public class CaptureException extends RuntimeException {

    public static final String BLOCKED_ADDRESS = "BLOCKED_ADDRESS";
    public static final String INVALID_URL = "INVALID_URL";
    public static final String UNAVAILABLE = "CAPTURE_UNAVAILABLE";

    private final String code;

    public CaptureException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 내부망 주소·잘못된 주소 — 등록 자체를 거절할 실패(Section 3.2) */
    public boolean rejectsUrl() {
        return BLOCKED_ADDRESS.equals(code) || INVALID_URL.equals(code);
    }
}
