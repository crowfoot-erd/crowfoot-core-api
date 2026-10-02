package net.java21.crowfoot.api.internal.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.internal.dto.ConnectionAccessRequest;
import net.java21.crowfoot.api.internal.dto.ConnectionAccessResponse;
import net.java21.crowfoot.api.internal.service.InternalConnectionAccessService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 전용 API — DB 매니저용 접근 확인 (08-core/15-internal-api.md Section 2.1).
 * Gateway 라우팅 제외(외부 경로 /api/v1/core/**와 무관), 내부망에서만 연다(Internal* 관례).
 * 응답에 복호화한 비밀번호가 실린다 — 이 컨트롤러의 요청·응답 본문을 로그에 남기지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class InternalConnectionAccessController {

    private final InternalConnectionAccessService accessService;

    @PostMapping("/internal/core/connections/{connectionId}/access")
    public ApiResponse<ConnectionAccessResponse> access(@PathVariable("connectionId") long connectionId,
                                                        @Valid @RequestBody ConnectionAccessRequest request) {
        return ApiResponse.success(accessService.access(
                connectionId, parseId(request.userId()), parseId(request.workspaceId()),
                Boolean.TRUE.equals(request.mcpWrite())));
    }

    /** ID는 문자열로 오간다(api-design.md) — 숫자가 아니면 형식 오류 */
    private static long parseId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
