package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 모델 생성 요청 (08-core/02-model.md Section 1.2) — 생성 시 대표 다이어그램(main)이 자동 생성된다.
 * databaseType은 코드 테이블(database_types)의 활성 코드여야 한다.
 * 캔버스 크기는 폐지(#123) — 에디터가 무한 캔버스라 크기 개념이 없고, 요청에서도 받지 않는다.
 */
public record CreateModelRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @NotBlank @Size(max = 50) String databaseType
) {
}
