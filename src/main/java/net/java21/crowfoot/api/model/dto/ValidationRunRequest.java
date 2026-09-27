package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 검증 실행 기록 요청 (08-core/02-model.md §1.13) — 에디터 검증 패널이 계산한 등급별 건수.
 * 검증 자체는 웹이 수행하므로 서버는 값을 재계산하지 않고 감사 detail로 원문을 남긴다.
 */
public record ValidationRunRequest(
        @NotNull @Min(0) @Max(100_000) Integer errorCount,
        @NotNull @Min(0) @Max(100_000) Integer warningCount,
        @NotNull @Min(0) @Max(100_000) Integer infoCount) {
}
