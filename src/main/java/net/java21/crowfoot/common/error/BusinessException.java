package net.java21.crowfoot.common.error;

import lombok.Getter;

/**
 * 도메인 규칙 위반 — GlobalExceptionHandler가 공통 실패 포맷으로 변환한다.
 * 문구를 도메인 컨텍스트에 맞게 덮어쓸 수 있다 (예: PERMISSION_DENIED + "설정 변경 권한이 없습니다").
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
