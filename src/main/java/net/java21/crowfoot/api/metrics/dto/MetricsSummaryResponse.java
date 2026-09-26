package net.java21.crowfoot.api.metrics.dto;

import java.util.List;

/**
 * 트래픽 요약 응답 (08-core/10-metrics.md Section 7) — 오늘·어제·전주 동일 요일 스냅샷과
 * 일별 계열. UUV = visitor_new + visitor_returning + visitor_estimated(Section 5.2).
 */
public record MetricsSummaryResponse(
        int days,
        Snapshot today,
        Snapshot yesterday,
        Snapshot lastWeekSameDay,
        List<DailyPoint> series
) {

    public record Snapshot(long pv, long uuv, long sessions, long newVisitors, long bot) {
    }

    public record DailyPoint(String date, long pv, long uuv, long sessions, long newVisitors) {
    }
}
