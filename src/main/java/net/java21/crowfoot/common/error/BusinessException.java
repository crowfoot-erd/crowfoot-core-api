package net.java21.crowfoot.common.error;

import lombok.Getter;

/**
 * 도메인 규칙 위반 — GlobalExceptionHandler가 공통 실패 포맷으로 변환한다.
 *
 * <p>문구 결정 우선순위 (api-design.md §5.7 — resultCode 불변·resultMessage만 로케일화):
 * <ol>
 * <li>{@link #of} 번들 키 — Accept-Language 로케일로 해석(미등록 키는 error.{code} 번들 → 4번으로 폴백)
 * <li>생성자 리터럴 문구(2·3인자 생성자) — 도메인 컨텍스트 문구("설정 변경 권한이 없습니다" 등)
 * <li>{@link ErrorCode} 기본 문구
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String messageKey;
    private final Object[] args;
    private final boolean customMessage;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null, null, false, errorCode.getDefaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, null, null, true, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, null, null, true, message, cause);
    }

    /** 번들 키 생성 — resultMessage를 Accept-Language 로케일(ko/en/ja/zh)로 해석한다. args는 {0} 치환값 */
    public static BusinessException of(ErrorCode errorCode, String messageKey, Object... args) {
        return new BusinessException(errorCode, messageKey, args, false, errorCode.getDefaultMessage(), null);
    }

    private BusinessException(ErrorCode errorCode, String messageKey, Object[] args,
                              boolean customMessage, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.messageKey = messageKey;
        this.args = args;
        this.customMessage = customMessage;
    }
}
