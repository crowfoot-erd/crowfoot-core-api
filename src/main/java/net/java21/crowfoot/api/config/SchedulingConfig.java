package net.java21.crowfoot.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치 스케줄링 활성 — 현재 예약 작업은 방문 이벤트 보존 정리(MetricsPruner, 04:30 KST)
 * 하나다. 배치가 늘면 잡 관리(ShedLock 등 분산 락)를 재검토한다(단일 인스턴스 운영 중에는
 * 중복 실행 여지가 없다).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
