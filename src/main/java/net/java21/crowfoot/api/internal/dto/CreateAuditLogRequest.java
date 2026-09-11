package net.java21.crowfoot.api.internal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 인증 이벤트 감사 기록 요청 (08-core/05-account.md Section 3.5) — 기록은 best-effort.
 */
public record CreateAuditLogRequest(
        String actorId,
        @NotBlank String action,
        String detail
) {
}
