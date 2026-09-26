package net.java21.crowfoot.api.metrics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 일별 지표 롤업 (08-core/10-metrics.md Section 5.2) — 1행 = (날짜 × 차원 × 키) 누적 카운트.
 * 영구 보존(삭제 배치 대상 아님)이며 증분은 UPSERT(ON CONFLICT … DO UPDATE)로만 일어난다 —
 * 증분 정확성은 visit_events의 플래그(dayFirst/visitorNew/sessionNew)가 보장한다.
 */
@Entity
@Table(name = "daily_metric_rollups", schema = "crowfoot_core")
@IdClass(DailyMetricRollupId.class)
@Getter
@Setter
@NoArgsConstructor
public class DailyMetricRollup {

    @Id
    private LocalDate dateKst;

    @Id
    private String dimension;

    /** 차원 값 — pv 계열은 all, country는 ISO 코드, share는 shareToken, 그 외는 정규화 값 */
    @Id
    @jakarta.persistence.Column(name = "key")
    private String metricKey;

    private long count;

    public DailyMetricRollup(LocalDate dateKst, String dimension, String metricKey, long count) {
        this.dateKst = dateKst;
        this.dimension = dimension;
        this.metricKey = metricKey;
        this.count = count;
    }
}
