package net.java21.crowfoot.api.metrics.dto;

import java.util.List;

/**
 * audit_logs 집계 응답 (08-core/10-metrics.md Section 7) — 일별 로그인(성공/실패)과
 * 감사 액션별 총수(기능 사용량). 수집 변경 없이 쌓인 원장을 읽기만 한다.
 */
public record MetricsActivityResponse(
        int days,
        List<LoginDay> logins,
        List<ActionCount> actions
) {

    public record LoginDay(String date, long succeeded, long failed) {
    }

    public record ActionCount(String action, long count) {
    }
}
