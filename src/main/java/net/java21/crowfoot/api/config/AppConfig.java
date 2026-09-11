package net.java21.crowfoot.api.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * 공통 빈 — 시계(Clock)·Querydsl(JPAQueryFactory)·내부 호출용 RestClient.
 * Clock은 테스트에서 고정 시각으로 교체 주입한다 (00-environment/testing.md — 시간 의존 로직).
 */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Querydsl — list·search 조회와 DTO projection의 공용 팩토리 */
    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }

    /**
     * 인증 서버 내부 호출용 — connect 1s / read 2s, 재시도 없음
     * (01-architecture/api-design.md Section 11 타임아웃 권장).
     */
    @Bean
    public RestClient authRestClient(AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(2_000);
        return RestClient.builder()
                .baseUrl(props.auth().baseUrl())
                .requestFactory(factory)
                .build();
    }
}
