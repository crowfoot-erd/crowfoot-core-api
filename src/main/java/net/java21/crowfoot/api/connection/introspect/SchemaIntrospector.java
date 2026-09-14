package net.java21.crowfoot.api.connection.introspect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * DBMS별 스키마 introspection 전략 (05-editor/04-dbms-engineering.md Section 3.2) —
 * Forward(DDL 방언)과 같은 확장 구조: 신규 DBMS 지원 = 이 인터페이스 구현 1개를
 * {@link Introspectors} 레지스트리에 추가하는 것으로 끝난다.
 *
 * <p>구현은 표준 {@code information_schema}(테이블·컬럼·PK·UK)와 DBMS 고유 카탈로그
 * (PG {@code pg_constraint} 복합 FK 순서·{@code pg_description} 코멘트, MySQL 확장 컬럼)을 섞어 쓴다.
 */
public interface SchemaIntrospector {

    /** 이 전략이 담당하는 database_types 코드 (예: {@code mysql}, {@code postgresql}) */
    String dbmsType();

    /** JDBC URL 조립 */
    String jdbcUrl(String host, int port, String databaseName);

    /** 접속 타임아웃 등 드라이버 속성 — 사용자 자격 증명은 호출부가 채운다 */
    default void applyDriverProperties(Properties props) {
    }

    /**
     * 스키마 조회 — 접속 수명은 호출부(ConnectionService)가 관리한다.
     * {@code schemaName}은 PostgreSQL처럼 스키마가 DB 하위 개념인 DBMS의 지정값(선택) —
     * null이면 DBMS 기본값(PG current_schema 등)을 쓰고, database = schema인 MySQL은 무시한다.
     */
    IntrospectedSchema introspect(Connection connection, String schemaName) throws SQLException;

    /**
     * 배포 등 문장 실행 앞 세션에 대상 스키마를 심는다(PG {@code search_path}) —
     * 없는 스키마면 {@code SQLException}. 스키마 개념이 없는 DBMS·미지정(null)은 아무 것도 하지 않는다.
     */
    default void applySessionSchema(Connection connection, String schemaName) throws SQLException {
    }

    /** 물리 타입 표기 → 공용 논리 타입 코드(DbmsTemplates 정방향 매핑의 역).
     *  매핑에 없으면 대문자 원문을 그대로 돌려준다(공용 폴백 규칙으로 DDL 재생성 시 원문 유지). */
    String commonTypeCode(String physicalType);
}
