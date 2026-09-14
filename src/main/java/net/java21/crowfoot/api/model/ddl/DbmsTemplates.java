package net.java21.crowfoot.api.model.ddl;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DBMS 템플릿 레지스트리 — 공용 논리 타입 카탈로그 + DBMS별 물리 타입 매핑·라벨
 * (05-editor/01-core.md §17 — crowfoot-web dbms.ts와 같은 원천).
 *
 * <p>content엔 공용 논리 타입 코드만 저장되고, 물리 표기는 생성 시점에 이 매핑으로
 * 조립된다(논리/물리 분리). 매핑이 없는 코드는 공용 코드를 그대로 쓴다.
 * 새 DBMS 지원은 TEMPLATES에 항목 1개 추가로 끝난다.
 *
 * <p><b>양쪽 동기 주의</b> — 노드 표시용 매핑이 프론트(dbms.ts)에, DDL 생성용이
 * 이곳에 존재한다. 매핑을 고칠 때는 양쪽을 함께 고쳐야 한다(04-dbms-engineering.md §1).
 * 리버스 엔지니어링(§3.2)은 이 템플릿에 역방향(물리→논리) 매핑을 얹는다.
 */
public final class DbmsTemplates {

    public record DbmsTemplate(String id, String label, Map<String, String> types) {
    }

    /** 길이(n) 지정 가능 타입 — 매핑값이 이미 괄호를 포함하면 사용자 지정 크기는 무시된다 */
    private static final Set<String> LENGTH_TYPES = Set.of("CHAR", "VARCHAR");

    /** 정밀도+스케일(p,s) 지정 가능 타입 */
    private static final Set<String> PRECISION_TYPES = Set.of("DECIMAL");

    private static final List<DbmsTemplate> TEMPLATES = List.of(
            new DbmsTemplate("common", "공용(논리)", Map.of()),
            new DbmsTemplate("mysql", "MySQL", Map.of(
                    "BOOLEAN", "TINYINT(1)",
                    "UUID", "CHAR(36)")),
            new DbmsTemplate("postgres", "PostgreSQL", Map.of(
                    "INT", "INTEGER",
                    "DATETIME", "TIMESTAMP",
                    "DOUBLE", "DOUBLE PRECISION",
                    "FLOAT", "REAL",
                    "BLOB", "BYTEA")),
            new DbmsTemplate("oracle", "Oracle", Map.ofEntries(
                    Map.entry("BIGINT", "NUMBER(19)"),
                    Map.entry("SMALLINT", "NUMBER(5)"),
                    Map.entry("VARCHAR", "VARCHAR2"),
                    Map.entry("TEXT", "CLOB"),
                    Map.entry("BOOLEAN", "NUMBER(1)"),
                    Map.entry("TIME", "TIMESTAMP"),
                    Map.entry("DATETIME", "TIMESTAMP"),
                    Map.entry("JSON", "CLOB"),
                    Map.entry("UUID", "RAW(16)"),
                    Map.entry("FLOAT", "BINARY_FLOAT"),
                    Map.entry("DOUBLE", "BINARY_DOUBLE"))),
            new DbmsTemplate("mssql", "SQL Server", Map.of(
                    "TEXT", "VARCHAR(MAX)",
                    "BOOLEAN", "BIT",
                    "TIMESTAMP", "DATETIME2",
                    "JSON", "NVARCHAR(MAX)",
                    "UUID", "UNIQUEIDENTIFIER",
                    "BLOB", "VARBINARY(MAX)",
                    "DOUBLE", "FLOAT")));

    private DbmsTemplates() {
    }

    public static DbmsTemplate byId(String id) {
        for (DbmsTemplate template : TEMPLATES) {
            if (template.id().equals(id)) {
                return template;
            }
        }
        return TEMPLATES.get(0);
    }

    /** 서버 database_types 코드 → 템플릿 id. 알 수 없는 코드는 공용(논리) 폴백 —
     *  코드 테이블이 템플릿보다 앞서 늘어나도 생성이 깨지지 않게(경고로 안내). */
    public static String templateIdForDatabase(String databaseType) {
        String code = databaseType == null ? "" : databaseType.trim().toLowerCase();
        if ("postgresql".equals(code)) {
            return "postgres";
        }
        for (DbmsTemplate template : TEMPLATES) {
            if (template.id().equals(code)) {
                return code;
            }
        }
        return "common";
    }

    /** 컬럼 물리 타입 조립 — 템플릿 매핑 뒤에 길이/정밀도를 붙인다. 매핑값이 이미 괄호를
     *  포함하면(TINYINT(1)·NUMBER(19)·VARCHAR(MAX)) 사용자 크기를 무시하고 매핑 그대로 —
     *  이중 괄호 방어. */
    public static String assembleType(DdlContent.Column column, String templateId) {
        String mapped = byId(templateId).types().getOrDefault(column.dataType(), column.dataType());
        if (mapped.contains("(")) {
            return mapped;
        }
        if (PRECISION_TYPES.contains(column.dataType()) && column.precision() != null) {
            int scale = column.scale() == null ? 0 : column.scale();
            return mapped + "(" + column.precision() + "," + scale + ")";
        }
        if (LENGTH_TYPES.contains(column.dataType()) && column.length() != null) {
            return mapped + "(" + column.length() + ")";
        }
        return mapped;
    }
}
