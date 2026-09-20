package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 버전 복원 요청 (08-core/02-model.md Section 1.11) — 과거 스냅샷을 새 버전으로 저장.
 * baseVersion은 클라이언트가 읽은 현재 버전(낙관적 잠금 — 일치하지 않으면 409 VERSION_CONFLICT).
 */
public record RestoreModelVersionRequest(
        @NotNull @PositiveOrZero Integer baseVersion
) {
}
