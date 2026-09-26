package net.java21.crowfoot.api.metrics.dto;

import java.util.List;

/**
 * 차원별 분포 응답 (08-core/10-metrics.md Section 7) — TOP 20 {key, count, share}.
 * share는 기간 내 해당 차원 총합 대비 비율(0~1)이다.
 */
public record MetricsBreakdownResponse(
        String dimension,
        int days,
        long total,
        List<Entry> entries
) {

    /**
     * @param key 차원 값(롤업 key 그대로 — share는 shareToken)
     * @param displayName 화면 표기명 — share 차원만 채운다(토큰→문서명, §5.2). 그 외 차원은 null(웹이 라벨 규칙으로 표기)
     */
    public record Entry(String key, long count, double share, String displayName) {
    }
}
