package net.java21.crowfoot.api;

import net.java21.crowfoot.api.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * core 서버(crowfoot-core-api) bootstrap — 설정·조립만 담당한다
 * (01-architecture/package-structure.md — base package net.java21.crowfoot).
 *
 * <p>공통 포맷(net.java21.crowfoot.common)은 별도 패키지 트리에 있으므로 스캔 범위를 부모로 확장한다.
 */
@SpringBootApplication(scanBasePackages = "net.java21.crowfoot")
@ConfigurationPropertiesScan(basePackageClasses = AppProperties.class)
public class CrowfootCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrowfootCoreApplication.class, args);
    }
}
