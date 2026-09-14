package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 문서 본체 저장 요청 (08-core/02-model.md Section 1.5) — 낙관적 잠금.
 * baseVersion은 클라이언트가 읽은 버전, content는 Canonical 문서 JSON 직렬화 전문(UTF-8 5MB 상한).
 */
public record SaveContentRequest(
        @NotNull @PositiveOrZero Integer baseVersion,
        @NotBlank String content
) {
}
