package net.java21.crowfoot.api.internal.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.internal.dto.CreateAuditLogRequest;
import net.java21.crowfoot.api.internal.service.InternalAuditService;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 전용 API — 인증 이벤트 감사 기록 (best-effort).
 */
@RestController
@RequiredArgsConstructor
public class InternalAuditController {

    private final InternalAuditService internalAuditService;

    @PostMapping("/internal/core/audit-logs")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> record(@Valid @RequestBody CreateAuditLogRequest request) {
        internalAuditService.record(request);
        return ApiResponse.success();
    }
}
