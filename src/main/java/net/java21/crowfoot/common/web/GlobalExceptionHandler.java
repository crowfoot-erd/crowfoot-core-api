package net.java21.crowfoot.common.web;

import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.common.ErrorResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
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
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getCode(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST.getCode(), ErrorCode.INVALID_REQUEST.getDefaultMessage(), errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST.getCode(), ErrorCode.INVALID_REQUEST.getDefaultMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND.getCode(), ErrorCode.RESOURCE_NOT_FOUND.getDefaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }
}
