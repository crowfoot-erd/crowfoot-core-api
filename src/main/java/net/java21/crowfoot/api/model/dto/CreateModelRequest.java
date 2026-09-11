package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 모델 생성 요청 (08-core/02-model.md Section 1.2) — 생성 시 대표 다이어그램(main)이 자동 생성된다.
 * databaseType은 코드 테이블(database_types)의 활성 코드여야 하고, 캔버스 크기는 에디터 초기 배경(px)이다.
 */
public record CreateModelRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @NotBlank @Size(max = 50) String databaseType,
        @NotNull @Min(1) @Max(100000) Integer canvasWidth,
        @NotNull @Min(1) @Max(100000) Integer canvasHeight
) {
}
