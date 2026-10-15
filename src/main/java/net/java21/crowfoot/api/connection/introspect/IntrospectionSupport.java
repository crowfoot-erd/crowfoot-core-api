package net.java21.crowfoot.api.connection.introspect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySqlIntrospector·PostgresIntrospector가 함께 쓰는 조립 단계 — 카탈로그 SQL은 DBMS마다 다르고(호출부가 넘긴다),
 * 결과 행을 테이블·PK·UK·FK로 모으는 규칙만 같다.
 */
final class IntrospectionSupport {

    private IntrospectionSupport() {
    }

    /** 테이블 목록 — SQL의 1열 이름, 2열 코멘트. 이름 순서를 지키는 맵으로 돌려준다 */
    static Map<String, TableBuilder> readTables(Connection connection, String sql, String schema) throws SQLException {
        Map<String, TableBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    builders.put(name, new TableBuilder(name, normalizeComment(rs.getString(2))));
                }
            }
        }
        return builders;
    }

    /**
     * PK·UK — 제약별로 컬럼을 ordinal 순서로 모아 테이블에 붙인다. SQL은 4열이 컬럼 이름, 3열이 제약 이름이고
     * 제약 종류·테이블 열 위치는 DBMS마다 달라 인자로 받는다. {@code groupByTable}이면 묶음 키가 (테이블, 제약 이름)이고
     * 아니면 제약 이름만이다(PostgreSQL은 제약 이름이 스키마 안에서 겹치지 않는다).
     */
    static void readKeys(Connection connection, String sql, String schema, Map<String, TableBuilder> builders,
                         int typeColumn, int tableColumn, boolean groupByTable) throws SQLException {
        Map<String, KeyBuilder> keys = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String keyName = rs.getString(3);
                    String keyId = groupByTable ? rs.getString(tableColumn) + ' ' + keyName : keyName;
                    KeyBuilder key = keys.get(keyId);
                    if (key == null) {
                        key = new KeyBuilder("PRIMARY KEY".equals(rs.getString(typeColumn)), rs.getString(tableColumn), keyName);
                        keys.put(keyId, key);
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
    }

    /** FK — SQL 열은 (제약 이름, 자식 테이블, 자식 컬럼, 부모 테이블, 부모 컬럼, ON DELETE, ON UPDATE),
     *  제약 안에서 컬럼 쌍 순서대로 정렬돼 있어야 한다. 제약 이름 → 조립 상태(행 순서 유지)를 돌려준다 */
    static Map<String, FkBuilder> readForeignKeys(Connection connection, String sql, String schema) throws SQLException {
        Map<String, FkBuilder> fkBuilders = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
        return fkBuilders;
    }

    /** 조립 끝 — 컬럼이 있는 FK만 남긴다 */
    static List<IntrospectedSchema.IntrospectedFk> buildForeignKeys(Map<String, FkBuilder> fkBuilders) {
        List<IntrospectedSchema.IntrospectedFk> foreignKeys = new ArrayList<>();
        for (FkBuilder fk : fkBuilders.values()) {
            if (!fk.childColumns.isEmpty()) {
                foreignKeys.add(fk.build());
            }
        }
        return List.copyOf(foreignKeys);
    }

    static IntrospectedSchema buildSchema(Map<String, TableBuilder> builders, Map<String, FkBuilder> fkBuilders) {
        return new IntrospectedSchema(
                builders.values().stream().map(TableBuilder::build).toList(),
                buildForeignKeys(fkBuilders));
    }

    /** 맨 앞 괄호가 맨 끝 괄호와 짝인가 — (a) or (b) 같은 식은 아니다. 작은따옴표 안의 괄호는 세지 않는다 */
    static boolean outerParensWrapAll(String text) {
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

    static String normalizeComment(String comment) {
        // MySQL은 빈 코멘트를 ''가 아니라 종종 NULL로 돌려준다
        return comment == null || comment.isBlank() ? null : comment;
    }

    static Integer getInteger(ResultSet rs, int index) throws SQLException {
        int value = rs.getInt(index);
        return rs.wasNull() ? null : value;
    }

    /** 테이블 조립 중간 상태 */
    static final class TableBuilder {
        private final String name;
        private final String comment;
        final List<IntrospectedSchema.IntrospectedColumn> columns = new ArrayList<>();
        final List<IntrospectedSchema.IntrospectedUnique> uniques = new ArrayList<>();
        final List<IntrospectedSchema.IntrospectedIndex> indexes = new ArrayList<>();
        final List<IntrospectedSchema.IntrospectedCheck> checks = new ArrayList<>();
        private String primaryKeyName;
        private List<String> primaryKeyColumns = List.of();

        TableBuilder(String name, String comment) {
            this.name = name;
            this.comment = comment;
        }

        /** 컬럼 코멘트를 뒤늦게 채운다 — PostgreSQL은 코멘트를 따로 조회한다 */
        void applyColumnComment(String columnName, String comment) {
            for (int i = 0; i < columns.size(); i++) {
                IntrospectedSchema.IntrospectedColumn column = columns.get(i);
                if (column.name().equals(columnName)) {
                    columns.set(i, new IntrospectedSchema.IntrospectedColumn(
                            column.name(), column.typeName(), column.length(), column.precision(), column.scale(),
                            column.nullable(), column.defaultValue(), column.autoIncrement(), comment,
                            column.generatedExpression(), column.generatedStored(), column.onUpdate(), column.identityAlways()));
                    return;
                }
            }
        }

        IntrospectedSchema.IntrospectedTable build() {
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

    /** FK 조립 중간 상태 — 행 순서(ordinal)대로 컬럼 쌍이 쌓인다. 규칙이 비면(MySQL 일부) NO ACTION */
    static final class FkBuilder {
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
            this.onDelete = onDelete == null ? "NO ACTION" : onDelete;
            this.onUpdate = onUpdate == null ? "NO ACTION" : onUpdate;
        }

        private IntrospectedSchema.IntrospectedFk build() {
            return new IntrospectedSchema.IntrospectedFk(
                    name, childTable, List.copyOf(childColumns), parentTable, List.copyOf(parentColumns),
                    onDelete, onUpdate);
        }
    }
}
