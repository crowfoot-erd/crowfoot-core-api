package net.java21.crowfoot.api.connection.introspect;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Introspector 레지스트리 (05-editor/04-dbms-engineering.md Section 3.2) —
 * database_types 코드 → 전략. 스프링이 주입한 구현체 목록으로 초기화되므로
 * 신규 DBMS는 {@code @Component} 전략 하나 추가로 자동 등록된다.
 */
@Component
public class Introspectors {

    private final Map<String, SchemaIntrospector> byDbmsType;

    public Introspectors(List<SchemaIntrospector> introspectors) {
        this.byDbmsType = introspectors.stream()
                .collect(Collectors.toUnmodifiableMap(SchemaIntrospector::dbmsType, Function.identity()));
    }

    /** 등록된 전략이 없으면 null — 호출부가 INVALID_DBMS_TYPE로 판정한다 */
    public SchemaIntrospector forDbmsType(String dbmsType) {
        return byDbmsType.get(dbmsType == null ? "" : dbmsType.trim().toLowerCase());
    }

    /** 전략에 맞는 URL·타임아웃으로 접속을 연다 — 자격 증명(평문 복호화본)은 호출부가 넘긴다 */
    public Connection open(SchemaIntrospector introspector, String host, int port, String databaseName,
                           String username, String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        introspector.applyDriverProperties(props);
        return DriverManager.getConnection(introspector.jdbcUrl(host, port, databaseName), props);
    }
}
