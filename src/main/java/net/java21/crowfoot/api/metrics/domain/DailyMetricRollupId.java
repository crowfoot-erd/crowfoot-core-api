package net.java21.crowfoot.api.metrics.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * daily_metric_rollups 복합키 (date_kst, dimension, key) — @IdClass용.
 */
public class DailyMetricRollupId implements Serializable {

    private LocalDate dateKst;
    private String dimension;
    private String metricKey;

    public DailyMetricRollupId() {
    }

    public DailyMetricRollupId(LocalDate dateKst, String dimension, String metricKey) {
        this.dateKst = dateKst;
        this.dimension = dimension;
        this.metricKey = metricKey;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DailyMetricRollupId that)) {
            return false;
        }
        return Objects.equals(dateKst, that.dateKst)
                && Objects.equals(dimension, that.dimension)
                && Objects.equals(metricKey, that.metricKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dateKst, dimension, metricKey);
    }
}
