package net.java21.crowfoot.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 실패 응답 — header + (선택) 필드 수준 errors.
 * (01-architecture/api-design.md Section 5.3 — 실패에는 response/responses가 없다)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(ResponseHeader header, List<FieldError> errors) {

    public record FieldError(String field, String code, String message) {
    }

    public static ErrorResponse of(String resultCode, String resultMessage) {
        return new ErrorResponse(ResponseHeader.fail(resultCode, resultMessage), null);
    }

    public static ErrorResponse of(String resultCode, String resultMessage, List<FieldError> errors) {
        return new ErrorResponse(ResponseHeader.fail(resultCode, resultMessage), errors);
    }
}
