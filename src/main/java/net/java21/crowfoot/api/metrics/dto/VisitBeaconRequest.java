package net.java21.crowfoot.api.metrics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 방문 비콘 요청 (08-core/10-metrics.md Section 3) — body는 최소만. UA·언어·IP는 서버가 헤더에서
 * 취하고, path·referrer 원문은 판정 직후 정규화·폐기된다.
 */
public record VisitBeaconRequest(

        /** 방문 경로(쿼리 제외, 언어 prefix 포함 원문) — 서버가 path_group으로 정규화 */
        @NotBlank @Size(max = 2000) String path,

        /** document.referrer 원문 — 서버가 도메인만 추출 후 폐기 */
        @Size(max = 2000) String referrer
) {
}
