package net.java21.crowfoot.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 성공 — 단일 리소스 / 실패 응답. (01-architecture/api-design.md Section 5.1·5.3)
 *
 * <p>실패 시 {@code response}는 null로도 내보내지 않고 필드 자체를 생략한다.
 * 데이터 없는 성공(예: Refresh 등록 201)도 {@code response}를 생략한 header만 응답한다.
 *
 * @param header   항상 존재
 * @param response 성공 시 단일 리소스 (선택)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(ResponseHeader header, T response) {

    public static <T> ApiResponse<T> success(T response) {
        return new ApiResponse<>(ResponseHeader.success(), response);
    }

    /** 데이터 없는 성공 — response 필드 생략 */
    public static ApiResponse<Void> success() {
        return new ApiResponse<>(ResponseHeader.success(), null);
    }

    public static ApiResponse<Void> fail(String resultCode, String resultMessage) {
        return new ApiResponse<>(ResponseHeader.fail(resultCode, resultMessage), null);
    }
}
