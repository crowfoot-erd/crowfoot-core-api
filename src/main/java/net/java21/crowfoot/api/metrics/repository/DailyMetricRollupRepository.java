package net.java21.crowfoot.api.metrics.repository;

import net.java21.crowfoot.api.metrics.domain.DailyMetricRollup;
import net.java21.crowfoot.api.metrics.domain.DailyMetricRollupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 일별 지표 롤업 접근 (JPA) — 증분 UPSERT(PostgreSQL ON CONFLICT)와 통계 화면 조회.
 * 쓰기는 오직 {@link #increment}뿐이다(앱 레벨 읽기-수정-쓰기 금지 — 증감 경합 방지).
 */
public interface DailyMetricRollupRepository extends JpaRepository<DailyMetricRollup, DailyMetricRollupId> {

    /**
     * 증분 UPSERT — 같은 (날짜 × 차원 × 키)면 count에 delta를 더한다.
     * PostgreSQL 전용 구문이라 H2 테스트에서는 실행하지 않는다(서비스 테스트는 리포지토리를 모킹,
     * 실물 PostgreSQL 검증은 로컬 스택 8082 실측으로 수행 — ModelShare의 원자 증감과 같은 배치).
     */
    @Modifying
    @Query(value = """
            INSERT INTO crowfoot_core.daily_metric_rollups (date_kst, dimension, key, count)
            VALUES (:date, :dimension, :metricKey, :delta)
            ON CONFLICT (date_kst, dimension, key)
            DO UPDATE SET count = daily_metric_rollups.count + EXCLUDED.count
            """, nativeQuery = true)
    void increment(@Param("date") LocalDate date, @Param("dimension") String dimension,
                   @Param("metricKey") String metricKey, @Param("delta") long delta);

    /** 통계 화면 조회 — 기간 내 행 전부(관리자 트래픽 대시보드가 이 테이블만 읽는다) */
    List<DailyMetricRollup> findByDateKstBetweenOrderByDateKstAsc(LocalDate from, LocalDate to);
}
