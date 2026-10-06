package net.java21.crowfoot.api.connection.introspect;

import java.util.List;

/**
 * Introspection 중립 결과 (05-editor/04-dbms-engineering.md Section 3.2) —
 * DBMS별 카탈로그 차이를 흡정한 테이블·컬럼·키·FK 모양. 공용 논리 코드 변환은
 * {@link ReverseContentAssembler}가 {@link SchemaIntrospector#commonTypeCode(String)}로 수행한다.
 *
 * <p>읽기 범위: BASE TABLE·컬럼(생성식·ON UPDATE·소수 초 포함)·PK·UK·FK·일반 인덱스(BTREE·FULLTEXT·SPATIAL)·
 * CHECK 제약(v1.34). 뷰는 읽지 않는다.
 */
public record IntrospectedSchema(List<IntrospectedTable> tables, List<IntrospectedFk> foreignKeys) {

    /** 테이블 — columns 순서가 카탈로그 순서(ordinal)다 */
    public record IntrospectedTable(
            String name,
            String comment,
            List<IntrospectedColumn> columns,
            String primaryKeyName,
            List<String> primaryKeyColumns,
            List<IntrospectedUnique> uniques,
            List<IntrospectedIndex> indexes,
            List<IntrospectedCheck> checks) {

        /** 인덱스·CHECK 없는 테이블 — v1.34 이전 꼴 */
        public IntrospectedTable(String name, String comment, List<IntrospectedColumn> columns,
                                 String primaryKeyName, List<String> primaryKeyColumns,
                                 List<IntrospectedUnique> uniques) {
            this(name, comment, columns, primaryKeyName, primaryKeyColumns, uniques, List.of(), List.of());
        }
    }

    /** 컬럼 — typeName은 방언 기본형(괄호 없음: mysql {@code varchar}, pg {@code varchar} 등).
     *  length는 CHAR·VARCHAR·BINARY·VARBINARY, precision/scale은 DECIMAL 계열, precision만은
     *  날짜시간의 소수 초(DBMS 기본값이면 null)다. generatedExpression이 있으면 생성 컬럼이다. */
    public record IntrospectedColumn(
            String name,
            String typeName,
            Integer length,
            Integer precision,
            Integer scale,
            boolean nullable,
            String defaultValue,
            boolean autoIncrement,
            String comment,
            String generatedExpression,
            boolean generatedStored,
            String onUpdate) {

        /** 생성식·ON UPDATE 없는 컬럼 — v1.34 이전 꼴 */
        public IntrospectedColumn(String name, String typeName, Integer length, Integer precision, Integer scale,
                                  boolean nullable, String defaultValue, boolean autoIncrement, String comment) {
            this(name, typeName, length, precision, scale, nullable, defaultValue, autoIncrement, comment,
                    null, false, null);
        }
    }

    /** 일반 인덱스 — 유니크 인덱스는 uniques로 간다. type은 BTREE·FULLTEXT·SPATIAL, parser는 MySQL 전문 검색 파서 */
    public record IntrospectedIndex(String name, List<IndexColumn> columns, String type, String parser) {
    }

    /** 인덱스 컬럼 — order는 ASC·DESC */
    public record IndexColumn(String name, String order) {
    }

    /** CHECK 제약 — expression은 바깥 괄호 없는 식 */
    public record IntrospectedCheck(String name, String expression) {
    }

    /** 유니크 키 — 컬럼 순서 보존 */
    public record IntrospectedUnique(String name, List<String> columns) {
    }

    /** FK — child가 FK를 소유한 테이블, onDelete/onUpdate는 카탈로그 원문("CASCADE"·"NO ACTION" 등).
     *  컬럼 쌍 순서가 매핑 순서다(부모 i번째 ↔ 자식 i번째). */
    public record IntrospectedFk(
            String name,
            String childTable,
            List<String> childColumns,
            String parentTable,
            List<String> parentColumns,
            String onDelete,
            String onUpdate) {
    }
}
