package net.java21.crowfoot.api.managed.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 발급 한도 지정 요청 (08-core/07-managed-database.md Section 3.2.1) —
 * 워크스페이스 내 사용자당 한도. 1~100.
 */
public record SetManagedIssueLimitRequest(
        @NotNull @Min(1) @Max(100) Integer limit
) {
}
