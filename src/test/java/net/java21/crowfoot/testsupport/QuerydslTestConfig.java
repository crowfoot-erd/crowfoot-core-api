package net.java21.crowfoot.testsupport;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** @DataJpaTest에서 Querydsl 리포지토리(@Repository + JPAQueryFactory 주입)를 쓰기 위한 공용 설정 */
@TestConfiguration
public class QuerydslTestConfig {

    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
