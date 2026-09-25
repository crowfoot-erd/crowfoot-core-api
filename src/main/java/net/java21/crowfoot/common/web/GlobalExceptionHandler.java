package net.java21.crowfoot.common.web;

import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.common.ErrorResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import net.java21.crowfoot.common.i18n.ServerMessages;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 전역 예외 처리 — 모든 실패를 공통 포맷(header resultCode/resultMessage, 실패 시 response 생략)으로 렌더링한다.
 * (01-architecture/api-design.md Section 5.3)
 *
 * <p>resultMessage는 Accept-Language 로케일로 해석한다(§5.7) — resultCode는 불변.
 * 우선순위: 번들 키(BusinessException.of) → 생성자 리터럴 문구 → error.{code} 번들 → 기본 문구.
 * 번들 미주입 상태(@WebMvcTest 슬라이스 등)에서는 리터럴·기본 문구로 폴백한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.getCode(), resolveMessage(ex)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getCode(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST.getCode(), codeMessage(ErrorCode.INVALID_REQUEST), errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST.getCode(), codeMessage(ErrorCode.INVALID_REQUEST)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND.getCode(), codeMessage(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR.getCode(), codeMessage(ErrorCode.INTERNAL_ERROR)));
    }

    /** BusinessException 문구 — BusinessException 우선순위 문서를 따른다 */
    private String resolveMessage(BusinessException ex) {
        if (ex.getMessageKey() != null) {
            String resolved = ServerMessages.resolve(ex.getMessageKey(), ex.getArgs(), null);
            if (resolved != null) {
                return resolved;
            }
        }
        if (ex.isCustomMessage() && ex.getMessage() != null) {
            return ex.getMessage();
        }
        return codeMessage(ex.getErrorCode());
    }

    /** ErrorCode 번들 문구 — 미등록 키·번들 미주입은 기본 문구(한국어)로 폴백 */
    private String codeMessage(ErrorCode code) {
        String resolved = ServerMessages.resolve(code.messageKey(), null, null);
        return resolved != null ? resolved : code.getDefaultMessage();
    }
}
