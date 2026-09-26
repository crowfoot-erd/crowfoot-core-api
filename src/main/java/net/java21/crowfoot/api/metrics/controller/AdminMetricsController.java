package net.java21.crowfoot.api.metrics.controller;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.metrics.dto.MetricsActivityResponse;
import net.java21.crowfoot.api.metrics.dto.MetricsBreakdownResponse;
import net.java21.crowfoot.api.metrics.dto.MetricsSummaryResponse;
import net.java21.crowfoot.api.metrics.service.AdminMetricsService;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 트래픽 통계 API (08-core/10-metrics.md Section 7) — 보호 경로(/core/admin/**,
 * AdminGuard 최종 판정 + 조회마다 감사 ADMIN_METRICS_VIEWED). 화면 계약은
 * 04-front/03-admin.md Section 9.
 */
@RestController
@RequiredArgsConstructor
public class AdminMetricsController {

    private final AdminMetricsService adminMetricsService;

    /** 요약 — 오늘·어제·전주 동일 요일 스냅샷 + 일별 계열. days는 7/28/90(기본 28) */
    @GetMapping("/core/admin/metrics/summary")
    public ApiResponse<MetricsSummaryResponse> summary(@RequestParam(name = "days", required = false) Integer days) {
        return ApiResponse.success(
                adminMetricsService.summary(CurrentUserHolder.get().userId(), days));
    }

    /** 차원별 분포 TOP 20 — dimension은 열거 화이트리스트(country·browser·os·device·lang·referrer·page·share) */
    @GetMapping("/core/admin/metrics/breakdown")
    public ApiResponse<MetricsBreakdownResponse> breakdown(
            @RequestParam("dimension") String dimension,
            @RequestParam(name = "days", required = false) Integer days) {
        return ApiResponse.success(
                adminMetricsService.breakdown(CurrentUserHolder.get().userId(), dimension, days));
    }

    /** audit_logs 집계 — 일별 로그인(성공/실패) + 액션별 총수(기능 사용량) */
    @GetMapping("/core/admin/metrics/activity")
    public ApiResponse<MetricsActivityResponse> activity(@RequestParam(name = "days", required = false) Integer days) {
        return ApiResponse.success(
                adminMetricsService.activity(CurrentUserHolder.get().userId(), days));
    }
}
