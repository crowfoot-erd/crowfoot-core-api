package net.java21.crowfoot.api.connection.introspect;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL introspection — {@code schemaName} 지정이 없으면 접속 DB의 기본 스키마
 * (current_schema, 보통 {@code public})를, 있으면 그 스키마를 읽는다.
 * 코멘트는 {@code obj_description}·{@code col_description},
 * 복합 FK의 컬럼 쌍 순서는 {@code pg_constraint.conkey/confkey} + unnest WITH ORDINALITY로
 * 정확히 잡는다(information_schema 조인은 복합 키에서 카테시안 위험이 있다).
 */
@Component
public class PostgresIntrospector implements SchemaIntrospector {

    /** 물리 타입(udt_name) → 공용 논리 코드 — DbmsTemplates.postgres 정방향의 역 */
    private static final Map<String, String> COMMON_TYPES = Map.ofEntries(
            Map.entry("int2", "SMALLINT"),
            Map.entry("int4", "INT"),
            Map.entry("int8", "BIGINT"),
            Map.entry("numeric", "DECIMAL"),
            Map.entry("float4", "FLOAT"),
            Map.entry("float8", "DOUBLE"),
            Map.entry("varchar", "VARCHAR"),
            Map.entry("bpchar", "CHAR"),
            Map.entry("char", "CHAR"),
            Map.entry("text", "TEXT"),
            Map.entry("bool", "BOOLEAN"),
            Map.entry("date", "DATE"),
            Map.entry("time", "TIME"),
            Map.entry("timetz", "TIME"),
            Map.entry("timestamp", "TIMESTAMP"),
            Map.entry("timestamptz", "TIMESTAMP"),
            Map.entry("json", "JSON"),
            Map.entry("jsonb", "JSON"),
            Map.entry("uuid", "UUID"),
            Map.entry("bytea", "BLOB"));

    /** PG 기본값 표현의 캐스트 접미사 — {@code '0'::integer}, {@code now()::timestamp(6)} */
    private static final Pattern CAST_SUFFIX = Pattern.compile("^(.*?)::[a-zA-Z][a-zA-Z0-9_ ()]*$");

    @Override
    public String dbmsType() {
        return "postgresql";
    }

    @Override
    public String jdbcUrl(String host, int port, String databaseName) {
        // database 생략 접속(매니지드 루트 자격) — pgjdbc 표준 폴백대로 username과 같은 database로 연다
        String database = databaseName == null || databaseName.isBlank() ? "" : "/" + databaseName;
        return "jdbc:postgresql://" + host + ":" + port + database;
    }

    @Override
    public void applyDriverProperties(Properties props) {
        props.setProperty("connectTimeout", "5");
        props.setProperty("socketTimeout", "10");
    }

    @Override
    public String commonTypeCode(String physicalType) {
        String normalized = physicalType == null ? "" : physicalType.trim().toLowerCase();
        return COMMON_TYPES.getOrDefault(normalized, physicalType == null ? "" : physicalType.trim().toUpperCase());
    }

    @Override
    public IntrospectedSchema introspect(Connection connection, String schemaName) throws SQLException {
        String schema = schemaName == null || schemaName.isBlank()
                ? currentSchema(connection)
                : requireSchemaExists(connection, schemaName.trim());

        Map<String, TableBuilder> builders = new LinkedHashMap<>();
        String tableSql = """
                SELECT c.relname, obj_description(c.oid, 'pg_class') AS comment
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind = 'r'
                ORDER BY c.relname
                """;
        try (PreparedStatement ps = connection.prepareStatement(tableSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    builders.put(name, new TableBuilder(name, normalizeComment(rs.getString(2))));
                }
            }
        }

        String columnSql = """
                SELECT table_name, column_name, udt_name, character_maximum_length,
                       numeric_precision, numeric_scale, is_nullable, column_default, is_identity
                FROM information_schema.columns
                WHERE table_schema = ?
                ORDER BY table_name, ordinal_position
                """;
        try (PreparedStatement ps = connection.prepareStatement(columnSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder builder = builders.get(rs.getString(1));
                    if (builder == null) {
                        continue;
                    }
                    String rawDefault = rs.getString(8);
                    boolean autoIncrement = "YES".equals(rs.getString(9))
                            || (rawDefault != null && rawDefault.startsWith("nextval("));
                    // serial의 nextval 기본값은 자동 증가로 흡수 — DEFAULT로 남기면 DDL 재생성 시 identity와 충돌
                    String defaultValue = autoIncrement ? null : stripCastSuffix(rawDefault);
                    builder.columns.add(new IntrospectedSchema.IntrospectedColumn(
                            rs.getString(2),
                            rs.getString(3),
                            getInteger(rs, 4),
                            getInteger(rs, 5),
                            getInteger(rs, 6),
                            "YES".equals(rs.getString(7)),
                            defaultValue,
                            autoIncrement,
                            null));
                }
            }
        }

        // 컬럼 코멘트 — pg_description을 통하는 col_description
        String commentSql = """
                SELECT c.relname, a.attname, col_description(c.oid, a.attnum) AS comment
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                WHERE n.nspname = ? AND c.relkind = 'r' AND col_description(c.oid, a.attnum) IS NOT NULL
                """;
        try (PreparedStatement ps = connection.prepareStatement(commentSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder builder = builders.get(rs.getString(1));
                    if (builder == null) {
                        continue;
                    }
                    builder.applyColumnComment(rs.getString(2), rs.getString(3));
                }
            }
        }

        // PK·UK — 그룹키 (constraint)별로 컬럼을 ordinal 순서로 모은다
        String keySql = """
                SELECT tc.table_name, tc.constraint_type, tc.constraint_name, kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.table_schema = tc.constraint_schema
                 AND kcu.constraint_name = tc.constraint_name
                 AND kcu.table_name = tc.table_name
                WHERE tc.table_schema = ? AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
                ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position
                """;
        Map<String, KeyBuilder> keys = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(keySql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String keyName = rs.getString(3);
                    KeyBuilder key = keys.get(keyName);
                    if (key == null) {
                        key = new KeyBuilder("PRIMARY KEY".equals(rs.getString(2)), rs.getString(1), keyName);
                        keys.put(keyName, key);
                    }
                    key.columns.add(rs.getString(4));
                }
            }
        }
        for (KeyBuilder key : keys.values()) {
            TableBuilder builder = builders.get(key.table);
            if (builder == null || key.columns.isEmpty()) {
                continue;
            }
            if (key.primary) {
                builder.primaryKeyName = key.name;
                builder.primaryKeyColumns = List.copyOf(key.columns);
            } else {
                builder.uniques.add(new IntrospectedSchema.IntrospectedUnique(key.name, List.copyOf(key.columns)));
            }
        }

        // FK — confdeltype/confupdtype 코드: a=NO ACTION r=RESTRICT c=CASCADE n=SET NULL d=SET DEFAULT
        List<IntrospectedSchema.IntrospectedFk> foreignKeys = new ArrayList<>();
        String fkSql = """
                SELECT con.conname,
                       child.relname, ca.attname,
                       parent.relname, pa.attname,
                       CASE con.confdeltype WHEN 'r' THEN 'RESTRICT' WHEN 'c' THEN 'CASCADE'
                            WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' ELSE 'NO ACTION' END,
                       CASE con.confupdtype WHEN 'r' THEN 'RESTRICT' WHEN 'c' THEN 'CASCADE'
                            WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' ELSE 'NO ACTION' END
                FROM pg_constraint con
                JOIN pg_class child ON child.oid = con.conrelid
                JOIN pg_class parent ON parent.oid = con.confrelid
                JOIN pg_namespace n ON n.oid = child.relnamespace
                JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS x(attnum, ord) ON true
                JOIN pg_attribute ca ON ca.attrelid = child.oid AND ca.attnum = x.attnum
                JOIN pg_attribute pa ON pa.attrelid = parent.oid AND pa.attnum = con.confkey[x.ord]
                WHERE con.contype = 'f' AND n.nspname = ?
                ORDER BY con.conname, x.ord
                """;
        Map<String, FkBuilder> fkBuilders = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(fkSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fkName = rs.getString(1);
                    FkBuilder fk = fkBuilders.get(fkName);
                    if (fk == null) {
                        fk = new FkBuilder(fkName, rs.getString(2), rs.getString(4), rs.getString(6), rs.getString(7));
                        fkBuilders.put(fkName, fk);
                    }
                    fk.childColumns.add(rs.getString(3));
                    fk.parentColumns.add(rs.getString(5));
                }
            }
        }
        for (FkBuilder fk : fkBuilders.values()) {
            if (!fk.childColumns.isEmpty()) {
                foreignKeys.add(fk.build());
            }
        }

        return new IntrospectedSchema(
                builders.values().stream().map(TableBuilder::build).toList(),
                List.copyOf(foreignKeys));
    }

    /** 기본 스키마 — JDBC getSchema()는 search_path와 다를 수 있어 서버에 직접 묻는다 */
    private static String currentSchema(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT current_schema()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** 배포 문장들이 스키마 한정자 없이 조립되므로 세션 search_path로 대상을 심는다 */
    @Override
    public void applySessionSchema(Connection connection, String schemaName) throws SQLException {
        if (schemaName == null || schemaName.isBlank()) {
            return;
        }
        String schema = requireSchemaExists(connection, schemaName.trim());
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('search_path', ?, false)")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    /** 없는 스키마면 그 사실을 알리는 예외 — 비어 있는 결과가 아니라 원인을 보여 준다 */
    private static String requireSchemaExists(Connection connection, String schema) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != 1) {
                    throw new SQLException("스키마 '" + schema + "'가 이 데이터베이스에 없습니다");
                }
            }
        }
        return schema;
    }

    private static String normalizeComment(String comment) {
        return comment == null || comment.isBlank() ? null : comment;
    }

    /** {@code '0'::integer} → {@code '0'} — 표기 정리. 내부 따옴표 그대로 둔다 */
    private static String stripCastSuffix(String defaultValue) {
        if (defaultValue == null) {
            return null;
        }
        Matcher matcher = CAST_SUFFIX.matcher(defaultValue.trim());
        return matcher.matches() ? matcher.group(1) : defaultValue.trim();
    }

    private static Integer getInteger(ResultSet rs, int index) throws SQLException {
        int value = rs.getInt(index);
        return rs.wasNull() ? null : value;
    }

    /** 테이블 조립 중간 상태 */
    private static final class TableBuilder {
        private final String name;
        private final String comment;
        private final List<IntrospectedSchema.IntrospectedColumn> columns = new ArrayList<>();
        private final List<IntrospectedSchema.IntrospectedUnique> uniques = new ArrayList<>();
        private String primaryKeyName;
        private List<String> primaryKeyColumns = List.of();

        private TableBuilder(String name, String comment) {
            this.name = name;
            this.comment = comment;
        }

        private void applyColumnComment(String columnName, String comment) {
            for (int i = 0; i < columns.size(); i++) {
                IntrospectedSchema.IntrospectedColumn column = columns.get(i);
                if (column.name().equals(columnName)) {
                    columns.set(i, new IntrospectedSchema.IntrospectedColumn(
                            column.name(), column.typeName(), column.length(), column.precision(), column.scale(),
                            column.nullable(), column.defaultValue(), column.autoIncrement(), comment));
                    return;
                }
            }
        }

        private IntrospectedSchema.IntrospectedTable build() {
            return new IntrospectedSchema.IntrospectedTable(
                    name, comment, List.copyOf(columns), primaryKeyName, primaryKeyColumns, List.copyOf(uniques));
        }
    }

    /** PK·UK 조립 중간 상태 */
    private static final class KeyBuilder {
        private final boolean primary;
        private final String table;
        private final String name;
        private final List<String> columns = new ArrayList<>();

        private KeyBuilder(boolean primary, String table, String name) {
            this.primary = primary;
            this.table = table;
            this.name = name;
        }
    }

    /** FK 조립 중간 상태 — unnest ordinal 순서대로 컬럼 쌍이 쌓인다 */
    private static final class FkBuilder {
        private final String name;
        private final String childTable;
        private final String parentTable;
        private final String onDelete;
        private final String onUpdate;
        private final List<String> childColumns = new ArrayList<>();
        private final List<String> parentColumns = new ArrayList<>();

        private FkBuilder(String name, String childTable, String parentTable, String onDelete, String onUpdate) {
            this.name = name;
            this.childTable = childTable;
            this.parentTable = parentTable;
            this.onDelete = onDelete;
            this.onUpdate = onUpdate;
        }

        private IntrospectedSchema.IntrospectedFk build() {
            return new IntrospectedSchema.IntrospectedFk(
                    name, childTable, List.copyOf(childColumns), parentTable, List.copyOf(parentColumns),
                    onDelete, onUpdate);
        }
    }
}
