package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 문서-데이터베이스 최초 연결 요청 (08-core/02-model.md Section 1.14) — 원천 커넥션.
 */
public record ConnectModelRequest(
        @NotBlank
        @Pattern(regexp = "\\d+", message = "{validation.connection.id.digit}")
        String connectionId) {
}
