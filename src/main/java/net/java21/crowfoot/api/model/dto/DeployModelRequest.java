package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 포워드 엔지니어링 배포 요청 (08-core/02-model.md Section 1.8) — 대상 커넥션.
 */
public record DeployModelRequest(
        @NotBlank
        @Pattern(regexp = "\\d+", message = "커넥션 식별자는 숫자여야 합니다")
        String connectionId) {
}
