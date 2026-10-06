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

    /** 물리 타입(udt_name·SQL 텍스트 표기) → 공용 논리 코드 — DbmsTemplates.postgres 정방향의 역.
     *  udt_name은 카탈로그 조회(introspection), 텍스트 표기는 SQL 가져오기(DdlTextParser) 경로다 —
     *  INTEGER·REAL처럼 텍스트로만 오는 표기도 접지 않으면 문서가 비정규형으로 저장돼
     *  마이그레이션 diff가 같은 타입을 영구 TYPE 변경으로 잡는다(MySQL은 원래 둘 다 갖춘다). */
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
            // timestamp(타임존 없음)↔DATETIME, timestamptz(UTC 순간)↔TIMESTAMP —
            // 정방향 매핑(DATETIME→TIMESTAMP, TIMESTAMP→TIMESTAMPTZ)과 왕복이 일치한다
            Map.entry("timestamp", "DATETIME"),
            Map.entry("timestamptz", "TIMESTAMP"),
            Map.entry("json", "JSON"),
            Map.entry("jsonb", "JSON"),
            Map.entry("uuid", "UUID"),
            Map.entry("bytea", "BLOB"),
            // SQL 텍스트 표기 — 카탈로그 udt_name과 뜻이 같은 철자
            Map.entry("integer", "INT"),
            Map.entry("int", "INT"),
            Map.entry("smallint", "SMALLINT"),
            Map.entry("bigint", "BIGINT"),
            Map.entry("decimal", "DECIMAL"),
            Map.entry("dec", "DECIMAL"),
            Map.entry("real", "FLOAT"),
            Map.entry("double", "DOUBLE"),
            Map.entry("double precision", "DOUBLE"),
            Map.entry("boolean", "BOOLEAN"));

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
                       numeric_precision, numeric_scale, is_nullable, column_default, is_identity,
                       datetime_precision, is_generated, generation_expression
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
                    String udtName = rs.getString(3);
                    // 날짜시간의 소수 초는 precision에 — PostgreSQL 기본값 6은 null로 둔다
                    Integer precision = getInteger(rs, 5);
                    if (FRACTIONAL_TYPES.contains(udtName)) {
                        Integer fsp = getInteger(rs, 10);
                        precision = fsp == null || fsp == DEFAULT_FRACTION ? null : fsp;
                    }
                    boolean generated = "ALWAYS".equals(rs.getString(11)) && rs.getString(12) != null;
                    builder.columns.add(new IntrospectedSchema.IntrospectedColumn(
                            rs.getString(2),
                            udtName,
                            getInteger(rs, 4),
                            precision,
                            getInteger(rs, 6),
                            "YES".equals(rs.getString(7)),
                            generated ? null : defaultValue,
                            autoIncrement,
                            null,
                            generated ? stripOuterParens(rs.getString(12)) : null,
                            true, // PostgreSQL 생성 컬럼은 STORED만 있다
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

        readChecks(connection, schema, builders);
        readIndexes(connection, schema, builders);

        return new IntrospectedSchema(
                builders.values().stream().map(TableBuilder::build).toList(),
                List.copyOf(foreignKeys));
    }

    /** 소수 초를 갖는 udt_name */
    private static final java.util.Set<String> FRACTIONAL_TYPES = java.util.Set.of("time", "timetz", "timestamp", "timestamptz");

    /** PostgreSQL의 소수 초 기본값 — 정밀도를 적지 않은 TIMESTAMP는 6이다 */
    private static final int DEFAULT_FRACTION = 6;

    /** CHECK 제약 — pg_get_constraintdef의 "CHECK ((식))"에서 식만. NOT VALID 표시는 버린다 */
    private static void readChecks(Connection connection, String schema, Map<String, TableBuilder> builders)
            throws SQLException {
        String sql = """
                SELECT c.relname, con.conname, pg_get_constraintdef(con.oid)
                FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE con.contype = 'c' AND n.nspname = ?
                ORDER BY c.relname, con.conname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder builder = builders.get(rs.getString(1));
                    String definition = rs.getString(3);
                    if (builder == null || definition == null) {
                        continue;
                    }
                    String expression = definition.replaceFirst("(?i)^CHECK\\s*", "")
                            .replaceFirst("(?i)\\s+NOT VALID$", "");
                    builder.checks.add(new IntrospectedSchema.IntrospectedCheck(
                            rs.getString(2), stripOuterParens(expression)));
                }
            }
        }
    }

    /** 일반 인덱스 — 제약이 만든 인덱스(PK·UK)·식 인덱스·btree 외 방식은 뺀다. 제약 없는 유니크 인덱스는 유니크 키로.
     *  indoption 비트 1이 DESC다 */
    private static void readIndexes(Connection connection, String schema, Map<String, TableBuilder> builders)
            throws SQLException {
        String sql = """
                SELECT t.relname, i.relname, a.attname, (ix.indoption[k.ord - 1] & 1) AS descending, ix.indisunique
                FROM pg_index ix
                JOIN pg_class t ON t.oid = ix.indrelid
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_am am ON am.oid = i.relam
                JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
                LEFT JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                WHERE n.nspname = ? AND NOT ix.indisprimary AND am.amname = 'btree'
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = ix.indexrelid)
                ORDER BY t.relname, i.relname, k.ord
                """;
        Map<String, List<IntrospectedSchema.IndexColumn>> columnsByIndex = new LinkedHashMap<>();
        Map<String, String> tableByIndex = new java.util.HashMap<>();
        java.util.Set<String> uniqueIndexes = new java.util.HashSet<>();
        java.util.Set<String> expressionIndexes = new java.util.HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String index = rs.getString(2);
                    if (!builders.containsKey(table)) {
                        continue;
                    }
                    tableByIndex.put(index, table);
                    if (rs.getBoolean(5)) {
                        uniqueIndexes.add(index); // 제약 없는 유니크 인덱스(CREATE UNIQUE INDEX) — 유니크 키로 읽는다
                    }
                    List<IntrospectedSchema.IndexColumn> columns = columnsByIndex.computeIfAbsent(index, k -> new ArrayList<>());
                    String column = rs.getString(3);
                    if (column == null) {
                        expressionIndexes.add(index); // attnum 0 — 식 인덱스
                        continue;
                    }
                    columns.add(new IntrospectedSchema.IndexColumn(column, rs.getInt(4) == 1 ? "DESC" : "ASC"));
                }
            }
        }
        for (Map.Entry<String, List<IntrospectedSchema.IndexColumn>> entry : columnsByIndex.entrySet()) {
            if (expressionIndexes.contains(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }
            TableBuilder builder = builders.get(tableByIndex.get(entry.getKey()));
            if (uniqueIndexes.contains(entry.getKey())) {
                builder.uniques.add(new IntrospectedSchema.IntrospectedUnique(entry.getKey(),
                        entry.getValue().stream().map(IntrospectedSchema.IndexColumn::name).toList()));
                continue;
            }
            builder.indexes.add(new IntrospectedSchema.IntrospectedIndex(
                    entry.getKey(), List.copyOf(entry.getValue()), "BTREE", null));
        }
    }

    /** 바깥 괄호 한 겹씩 벗기기 — "((a > 0))" → "a > 0". (a) OR (b)처럼 앞뒤 괄호가 짝이 아니면 멈춘다 */
    static String stripOuterParens(String expression) {
        if (expression == null) {
            return null;
        }
        String text = expression.strip();
        while (text.startsWith("(") && text.endsWith(")") && wrapsAll(text)) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }

    private static boolean wrapsAll(String text) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
                if (depth == 0 && i < text.length() - 1) {
                    return false;
                }
            }
        }
        return true;
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
        private final List<IntrospectedSchema.IntrospectedIndex> indexes = new ArrayList<>();
        private final List<IntrospectedSchema.IntrospectedCheck> checks = new ArrayList<>();
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
                            column.nullable(), column.defaultValue(), column.autoIncrement(), comment,
                            column.generatedExpression(), column.generatedStored(), column.onUpdate()));
                    return;
                }
            }
        }

        private IntrospectedSchema.IntrospectedTable build() {
            return new IntrospectedSchema.IntrospectedTable(
                    name, comment, List.copyOf(columns), primaryKeyName, primaryKeyColumns, List.copyOf(uniques),
                    List.copyOf(indexes), List.copyOf(checks));
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
