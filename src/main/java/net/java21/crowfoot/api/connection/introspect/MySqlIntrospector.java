package net.java21.crowfoot.api.connection.introspect;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.java21.crowfoot.api.connection.introspect.IntrospectionSupport.FkBuilder;
import net.java21.crowfoot.api.connection.introspect.IntrospectionSupport.TableBuilder;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static net.java21.crowfoot.api.connection.introspect.IntrospectionSupport.getInteger;
import static net.java21.crowfoot.api.connection.introspect.IntrospectionSupport.normalizeComment;
import static net.java21.crowfoot.api.connection.introspect.IntrospectionSupport.outerParensWrapAll;

/**
 * MySQL introspection — database = schema라 접속 DB가 곧 대상이다.
 * 코멘트는 MySQL 확장 컬럼({@code TABLE_COMMENT}·{@code COLUMN_COMMENT}),
 * FK 참조 대상은 {@code KEY_COLUMN_USAGE.REFERENCED_*} 확장 컬럼, rule은
 * {@code REFERENTIAL_CONSTRAINTS}에서 읽는다.
 */
@Component
public class MySqlIntrospector implements SchemaIntrospector {

    /** 물리 타입(data_type 기본형, 소문자) → 공용 논리 코드 — DbmsTemplates.mysql 정방향의 역 */
    private static final Map<String, String> COMMON_TYPES = Map.ofEntries(
            Map.entry("int", "INT"),
            Map.entry("integer", "INT"),
            Map.entry("bigint", "BIGINT"),
            Map.entry("smallint", "SMALLINT"),
            Map.entry("decimal", "DECIMAL"),
            Map.entry("numeric", "DECIMAL"),
            Map.entry("float", "FLOAT"),
            Map.entry("double", "DOUBLE"),
            Map.entry("char", "CHAR"),
            Map.entry("varchar", "VARCHAR"),
            Map.entry("tinytext", "TEXT"),
            Map.entry("text", "TEXT"),
            Map.entry("mediumtext", "MEDIUMTEXT"),
            Map.entry("longtext", "LONGTEXT"),
            Map.entry("binary", "BINARY"),
            Map.entry("varbinary", "VARBINARY"),
            Map.entry("tinyblob", "BLOB"),
            Map.entry("blob", "BLOB"),
            Map.entry("mediumblob", "BLOB"),
            Map.entry("longblob", "BLOB"),
            Map.entry("date", "DATE"),
            Map.entry("time", "TIME"),
            Map.entry("datetime", "DATETIME"),
            Map.entry("timestamp", "TIMESTAMP"),
            Map.entry("json", "JSON"),
            // BOOLEAN은 TINYINT(1)의 별칭이다 — 카탈로그는 tinyint로 돌려주므로 COLUMN_TYPE으로 가려 boolean으로 넘긴다
            Map.entry("boolean", "BOOLEAN"),
            Map.entry("bool", "BOOLEAN"));

    @Override
    public String dbmsType() {
        return "mysql";
    }

    @Override
    public String jdbcUrl(String host, int port, String databaseName) {
        // database 없이 접속 가능(매니지드 루트 자격 검증·프로비저닝) — 일반 커넥션은 항상 지정된다
        String database = databaseName == null || databaseName.isBlank() ? "" : "/" + databaseName;
        return "jdbc:mysql://" + host + ":" + port + database;
    }

    @Override
    public void applyDriverProperties(Properties props) {
        // 접속·응답 타임아웃 5초/10초 (06-connection.md — 접속 테스트 기준 5초)
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "10000");
        // caching_sha2_password 기본 인증은 신규 계정 첫 접속이 풀 인증이라 서버 RSA 공개키가 필요하다 —
        // TLS 가능하면 TLS로, 아니면 공개키 검색 허용으로. 둘 다 막으면 발급 계정(cf_*) 접속이
        // "Public Key Retrieval is not allowed"로 실패한다(useSSL=false 단독의 함정)
        props.setProperty("sslMode", "PREFERRED");
        props.setProperty("allowPublicKeyRetrieval", "true");
    }

    @Override
    public String commonTypeCode(String physicalType) {
        String normalized = physicalType == null ? "" : physicalType.trim().toLowerCase(Locale.ROOT);
        return COMMON_TYPES.getOrDefault(normalized, physicalType == null ? "" : physicalType.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public IntrospectedSchema introspect(Connection connection, String schemaName) throws SQLException {
        // MySQL은 database = schema — 접속 DB가 곧 대상이라 schemaName을 쓰지 않는다
        String schema = connection.getCatalog();

        String tableSql = """
                SELECT TABLE_NAME, TABLE_COMMENT
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
                ORDER BY TABLE_NAME
                """;
        Map<String, TableBuilder> builders = IntrospectionSupport.readTables(connection, tableSql, schema);

        String columnSql = """
                SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
                       NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT,
                       DATETIME_PRECISION, GENERATION_EXPRESSION, COLUMN_TYPE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = ?
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """;
        try (PreparedStatement ps = connection.prepareStatement(columnSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder builder = builders.get(rs.getString(1));
                    if (builder == null) {
                        continue;
                    }
                    String extra = rs.getString(9) == null ? "" : rs.getString(9);
                    String dataType = "tinyint(1)".equalsIgnoreCase(rs.getString(13)) ? "boolean" : rs.getString(3);
                    String lowerExtra = extra.toLowerCase(Locale.ROOT);
                    String generation = rs.getString(12);
                    boolean generated = lowerExtra.contains("generated") && !lowerExtra.contains("default_generated")
                            && generation != null && !generation.isBlank();
                    // 날짜시간의 소수 초는 precision에 — MySQL 기본값 0은 null로 둔다
                    Integer precision = getInteger(rs, 5);
                    if (FRACTIONAL_TYPES.contains(dataType.toLowerCase(Locale.ROOT))) {
                        Integer fsp = getInteger(rs, 11);
                        precision = fsp == null || fsp == 0 ? null : fsp;
                    }
                    builder.columns.add(new IntrospectedSchema.IntrospectedColumn(
                            rs.getString(2),
                            dataType,
                            getInteger(rs, 4),
                            precision,
                            getInteger(rs, 6),
                            !"NO".equals(rs.getString(7)),
                            generated ? null : columnDefault(rs.getString(8), lowerExtra),
                            extra.contains("auto_increment"),
                            normalizeComment(rs.getString(10)),
                            generated ? cleanExpression(generation) : null,
                            lowerExtra.contains("stored generated"),
                            onUpdate(extra)));
                }
            }
        }

        // PK·UK — 그룹키 (constraint type, name)별로 컬럼을 ordinal 순서로 모은다
        String keySql = """
                SELECT tc.CONSTRAINT_TYPE, tc.TABLE_NAME, tc.CONSTRAINT_NAME, kcu.COLUMN_NAME
                FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                  ON kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                 AND kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                 AND kcu.TABLE_NAME = tc.TABLE_NAME
                WHERE tc.TABLE_SCHEMA = ? AND tc.CONSTRAINT_TYPE IN ('PRIMARY KEY', 'UNIQUE')
                ORDER BY tc.TABLE_NAME, tc.CONSTRAINT_NAME, kcu.ORDINAL_POSITION
                """;
        IntrospectionSupport.readKeys(connection, keySql, schema, builders, 1, 2, true);

        readChecks(connection, schema, builders);

        String fkSql = """
                SELECT kcu.CONSTRAINT_NAME, kcu.TABLE_NAME, kcu.COLUMN_NAME,
                       kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME,
                       rc.DELETE_RULE, rc.UPDATE_RULE
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
                  ON rc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
                 AND rc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
                WHERE kcu.TABLE_SCHEMA = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY kcu.CONSTRAINT_NAME, kcu.ORDINAL_POSITION
                """;
        Map<String, FkBuilder> fkBuilders = IntrospectionSupport.readForeignKeys(connection, fkSql, schema);
        readIndexes(connection, schema, builders, fkBuilders.keySet());

        return IntrospectionSupport.buildSchema(builders, fkBuilders);
    }

    /** 소수 초를 갖는 타입 */
    private static final java.util.Set<String> FRACTIONAL_TYPES = java.util.Set.of("time", "datetime", "timestamp");

    /** ON UPDATE 값 — EXTRA의 "on update CURRENT_TIMESTAMP(6)"에서 */
    private static final java.util.regex.Pattern ON_UPDATE = onUpdatePattern();

    /** 정규식은 메서드에서 컴파일한다 — ReDoS 검토 결과를 이 정규식에만 한정해 억제하려고(필드 단위 억제는 SpotBugs가 받지 않는다) */
    @SuppressFBWarnings(value = "REDOS",
            justification = "끝 고정이 없는 find()이고 선택 그룹이 빈 매치로 바로 성공해 역추적이 없다 — 입력은 INFORMATION_SCHEMA.COLUMNS.EXTRA(짧은 서버 값)")
    private static java.util.regex.Pattern onUpdatePattern() {
        return java.util.regex.Pattern.compile("(?i)on update (\\S+(?:\\([^)]*\\))?)");
    }

    /** 전문 검색 파서 — SHOW CREATE TABLE의 "FULLTEXT KEY `ft` (…) /*!50100 WITH PARSER `ngram` *&#47;" */
    private static final java.util.regex.Pattern FULLTEXT_PARSER = java.util.regex.Pattern.compile(
            "(?i)FULLTEXT KEY `([^`]+)`[^\\n]*?WITH PARSER `?(\\w+)`?");

    static String onUpdate(String extra) {
        java.util.regex.Matcher matcher = ON_UPDATE.matcher(extra == null ? "" : extra);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** 기본값 — 식 기본값(EXTRA DEFAULT_GENERATED)은 괄호로 감싸 식임을 남긴다. 시각 키워드는 그대로 */
    static String columnDefault(String value, String lowerExtra) {
        if (value == null) {
            return null;
        }
        if (lowerExtra.contains("default_generated")) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("current_timestamp") || lower.startsWith("now(") || lower.startsWith("current_date")
                    || lower.startsWith("current_time") || lower.startsWith("localtime")) {
                return value.toUpperCase(Locale.ROOT);
            }
            return "(" + cleanExpression(value) + ")";
        }
        return value;
    }

    /** 카탈로그가 돌려주는 식 정리 — 백틱·문자셋 접두(_utf8mb4'x')·\' 이스케이프를 걷고 바깥 괄호 한 겹을 벗긴다 */
    static String cleanExpression(String expression) {
        if (expression == null) {
            return null;
        }
        String cleaned = expression.replace("\\'", "'")
                .replaceAll("_[a-z0-9]+(?=')", "")
                .replace("`", "")
                .strip();
        while (cleaned.startsWith("(") && cleaned.endsWith(")") && outerParensWrapAll(cleaned)) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
        }
        return cleaned;
    }

    /** CHECK 제약 — 8.0.16+의 CHECK_CONSTRAINTS. 그 전 버전(테이블 없음)은 읽지 않는다 */
    private static void readChecks(Connection connection, String schema, Map<String, TableBuilder> builders) {
        String sql = """
                SELECT tc.TABLE_NAME, cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
                FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
                 AND tc.CONSTRAINT_TYPE = 'CHECK'
                WHERE cc.CONSTRAINT_SCHEMA = ?
                ORDER BY tc.TABLE_NAME, cc.CONSTRAINT_NAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder builder = builders.get(rs.getString(1));
                    if (builder != null) {
                        builder.checks.add(new IntrospectedSchema.IntrospectedCheck(
                                rs.getString(2), cleanExpression(rs.getString(3))));
                    }
                }
            }
        } catch (SQLException ignored) {
            // MySQL 8.0.16 이전·MariaDB 일부 — CHECK를 읽지 않고 진행한다
        }
    }

    /** 일반 인덱스 — STATISTICS. 유니크(UK로 읽힘)·PRIMARY·FK가 만든 인덱스(FK 이름과 같은 이름)는 뺀다.
     *  v1.37: 함수 키 조각(8.0.13+ EXPRESSION)은 식 인덱스로, 식이 든 유니크 인덱스는 유니크 인덱스로, HASH는 HASH로 읽는다 */
    @SuppressFBWarnings(value = "SQL_INJECTION_JDBC",
            justification = "SHOW CREATE TABLE은 바인드 변수를 받지 않는다 — 테이블명은 INFORMATION_SCHEMA에서 읽은 값이고 백틱으로 감싸며 안의 백틱은 두 겹으로 이스케이프한다")
    private static void readIndexes(Connection connection, String schema, Map<String, TableBuilder> builders,
                                    java.util.Set<String> foreignKeyNames) throws SQLException {
        Map<String, IndexBuilder> indexes = new LinkedHashMap<>();
        try {
            readIndexRows(connection, schema, builders, foreignKeyNames, indexes, true);
        } catch (SQLException noExpressionColumn) {
            // EXPRESSION 열이 없는 서버(MySQL 8.0.13 이전·MariaDB) — 식 키 없이 다시 읽는다
            indexes.clear();
            readIndexRows(connection, schema, builders, foreignKeyNames, indexes, false);
        }
        java.util.Set<String> fulltextTables = new java.util.HashSet<>();
        for (IndexBuilder index : indexes.values()) {
            if ("FULLTEXT".equals(index.type)) {
                fulltextTables.add(index.table);
            }
        }
        Map<String, String> parsers = new java.util.HashMap<>();
        for (String table : fulltextTables) {
            try (PreparedStatement ps = connection.prepareStatement("SHOW CREATE TABLE `" + table.replace("`", "``") + "`");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.util.regex.Matcher matcher = FULLTEXT_PARSER.matcher(rs.getString(2));
                    while (matcher.find()) {
                        parsers.put(table + ' ' + matcher.group(1), matcher.group(2));
                    }
                }
            }
        }
        for (IndexBuilder index : indexes.values()) {
            boolean expression = index.parts.stream().anyMatch(part -> part == null);
            if (index.unique && !expression) {
                continue; // 컬럼만으로 된 유니크는 유니크 키로 읽었다
            }
            String type = "FULLTEXT".equals(index.type) || "SPATIAL".equals(index.type) || "HASH".equals(index.type)
                    ? index.type : "BTREE";
            if (expression) {
                if (index.expressions.contains(null)) {
                    continue; // 식을 읽지 못한 서버
                }
                builders.get(index.table).indexes.add(new IntrospectedSchema.IntrospectedIndex(index.name, List.of(), type,
                        null, index.unique, String.join(", ", index.expressions), null, List.of()));
                continue;
            }
            builders.get(index.table).indexes.add(new IntrospectedSchema.IntrospectedIndex(
                    index.name, List.copyOf(index.parts), type, parsers.get(index.table + ' ' + index.name)));
        }
    }

    /** STATISTICS 조회 — EXPRESSION 열(8.0.13+) 유무만 다르다 */
    private static final String INDEX_ROWS_SQL = "SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, COLLATION, INDEX_TYPE, NON_UNIQUE";
    private static final String INDEX_ROWS_FROM = " FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = ? AND INDEX_NAME <> 'PRIMARY'"
            + " ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX";
    private static final String INDEX_ROWS_WITH_EXPRESSION_SQL = INDEX_ROWS_SQL + ", EXPRESSION" + INDEX_ROWS_FROM;
    private static final String INDEX_ROWS_WITHOUT_EXPRESSION_SQL = INDEX_ROWS_SQL + INDEX_ROWS_FROM;

    private static void readIndexRows(Connection connection, String schema, Map<String, TableBuilder> builders,
                                      java.util.Set<String> foreignKeyNames, Map<String, IndexBuilder> indexes,
                                      boolean withExpression) throws SQLException {
        String sql = withExpression ? INDEX_ROWS_WITH_EXPRESSION_SQL : INDEX_ROWS_WITHOUT_EXPRESSION_SQL;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String name = rs.getString(2);
                    if (!builders.containsKey(table) || foreignKeyNames.contains(name)) {
                        continue;
                    }
                    String indexType = rs.getString(5);
                    boolean unique = rs.getInt(6) == 0;
                    IndexBuilder index = indexes.computeIfAbsent(table + ' ' + name,
                            key -> new IndexBuilder(table, name, indexType, unique));
                    String column = rs.getString(3);
                    String order = "D".equals(rs.getString(4)) ? "DESC" : "ASC";
                    // 식 키는 COLUMN_NAME이 null이고 EXPRESSION에 식이 있다 — 조각마다 괄호로 감싼다(MySQL 함수 키 조각 표기)
                    String expression = withExpression ? rs.getString(7) : null;
                    index.parts.add(column == null ? null : new IntrospectedSchema.IndexColumn(column, order));
                    index.expressions.add(column != null ? column + ("DESC".equals(order) ? " DESC" : "")
                            : expression == null ? null : "(" + expression + ")" + ("DESC".equals(order) ? " DESC" : ""));
                }
            }
        }
    }

    /** 인덱스 조립 중간 상태 */
    private static final class IndexBuilder {
        private final String table;
        private final String name;
        private final String type;
        private final boolean unique;
        /** 키 조각 — 식 조각은 null */
        private final List<IntrospectedSchema.IndexColumn> parts = new ArrayList<>();
        /** 키 조각 원문 — 식 인덱스의 키 목록. 식을 읽지 못한 조각은 null */
        private final List<String> expressions = new ArrayList<>();

        private IndexBuilder(String table, String name, String type, boolean unique) {
            this.table = table;
            this.name = name;
            this.type = type;
            this.unique = unique;
        }
    }
}
