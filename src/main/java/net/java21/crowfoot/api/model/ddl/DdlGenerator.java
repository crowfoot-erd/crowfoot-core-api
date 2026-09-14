package net.java21.crowfoot.api.model.ddl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DDL(SQL) 생성 조립 골격 — Forward Engineering v1 (05-editor/04-dbms-engineering.md §3.1).
 *
 * <p>템플릿 메서드: 문서와 방언만 넘기면 SQL 스크립트와 경고 목록을 돌려준다.
 * 조립 순서 — CREATE TABLE(PK·UK 인라인) 전부 → ALTER TABLE FK 전부 →
 * CREATE INDEX → 코멘트 문장. FK를 별도 ALTER로 내보내기 때문에 테이블 간 의존
 * 순서(위상 정렬)가 필요 없다 — 상호 참조 A↔B도 안전하다.
 * 순수 문자열 조립이라 수백 테이블 규모도 즉시 끝난다.
 *
 * <p>식별자 인용부호는 v1에 없다(물리명 원문) — 제약 이름은 문서 전체 단일
 * 네임스페이스(§18)라 DDL에서 이름이 충돌하지 않는다. 검증 오류는 생성을 막지
 * 않고 경고(VALIDATION)로 먼저 알린다.
 */
public final class DdlGenerator {

    /** 생성 경고 — Capability 선표시(§3.1): 스크립트 앞에 목록으로 보여준다 */
    public record Warning(String code, String message) {
        public static final String COMMON_DIALECT = "COMMON_DIALECT";
        public static final String VALIDATION = "VALIDATION";
        public static final String EMPTY_TABLE = "EMPTY_TABLE";
    }

    public record Result(String sql, List<Warning> warnings, List<String> statements) {
    }

    /** referential action SQL 표기 — NO_ACTION·알 수 없는 값은 절 자체를 생략한다 */
    private static final Map<String, String> ACTION_SQL = Map.of(
            "RESTRICT", "RESTRICT",
            "CASCADE", "CASCADE",
            "SET_NULL", "SET NULL",
            "SET_DEFAULT", "SET DEFAULT");

    private DdlGenerator() {
    }

    public static Result generate(DdlContent content, SqlDialect dialect, String dbmsLabel, String modelName) {
        List<Warning> warnings = new ArrayList<>();

        // 공용(논리) 방언 — databaseType 코드가 템플릿에 등록되지 않은 결과다.
        // 물리 타입이 아닌 논리 표기로 생성되므로 항상 알린다
        if ("common".equals(dialect.id())) {
            warnings.add(new Warning(Warning.COMMON_DIALECT,
                    "이 문서의 DBMS는 SQL 방언이 등록되지 않아 공용(논리) 표기로 생성했습니다"));
        }
        warnings.addAll(validationWarnings(content));

        List<String> creates = new ArrayList<>();
        for (DdlContent.Table table : content.tables()) {
            if (table.columns().isEmpty()) {
                warnings.add(new Warning(Warning.EMPTY_TABLE,
                        "컬럼이 없어 생성에서 제외한 테이블: " + table.physicalName()));
                continue;
            }
            creates.add(createTableStatement(table, dialect));
        }
        List<String> foreignKeys = foreignKeyStatements(content);
        List<String> indexes = new ArrayList<>();
        for (DdlContent.Table table : content.tables()) {
            for (DdlContent.Index index : table.indexes()) {
                indexes.add(dialect.createIndex(table, index) + ";");
            }
        }
        List<String> comments = new ArrayList<>();
        for (DdlContent.Table table : content.tables()) {
            for (String statement : dialect.commentStatements(table)) {
                comments.add(statement + ";");
            }
        }

        String title = modelName == null || modelName.isBlank() ? "" : modelName + " — ";
        String header = "-- " + title + dbmsLabel + " DDL";

        List<String> blocks = new ArrayList<>();
        blocks.add(header);
        addIfNotEmpty(blocks, creates);
        addIfNotEmpty(blocks, foreignKeys);
        addIfNotEmpty(blocks, indexes);
        addIfNotEmpty(blocks, comments);

        // 실행 단위 목록(헤더 주석 제외) — Forward Engineering 배포가 문장별로 실행한다
        List<String> statements = new ArrayList<>(creates);
        statements.addAll(foreignKeys);
        statements.addAll(indexes);
        statements.addAll(comments);
        return new Result(String.join("\n\n", blocks), List.copyOf(warnings), List.copyOf(statements));
    }

    /* ---------- 검증 경고 — 에디터 validateModel error 규칙과 같은 기준 ---------- */

    private static List<Warning> validationWarnings(DdlContent content) {
        List<Warning> warnings = new ArrayList<>();

        // 키 이름 네임스페이스 — PK·UK·인덱스·FK 제약 이름을 모은다(공백 없음·대소문자 무시)
        // 중복 보고는 UK·인덱스에만 건다 — 그 둘이 이 규칙의 편집 대상이다
        Map<String, Integer> keyCounts = new java.util.HashMap<>();
        for (DdlContent.Table table : content.tables()) {
            if (table.primaryKey() != null) {
                bump(keyCounts, table.primaryKey().name());
            }
            for (DdlContent.KeyConstraint unique : table.uniques()) {
                bump(keyCounts, unique.name());
            }
            for (DdlContent.Index index : table.indexes()) {
                bump(keyCounts, index.name());
            }
        }
        for (DdlContent.Relationship relationship : content.relationships()) {
            bump(keyCounts, relationship.fkName());
        }

        Map<String, Integer> tableNames = new java.util.HashMap<>();
        for (DdlContent.Table table : content.tables()) {
            bump(tableNames, table.physicalName());
        }

        for (DdlContent.Table table : content.tables()) {
            if (tableNames.getOrDefault(key(table.physicalName()), 0) > 1) {
                warnings.add(new Warning(Warning.VALIDATION,
                        "테이블 물리명이 중복입니다: " + table.physicalName()));
            }
            Map<String, Integer> columnNames = new java.util.HashMap<>();
            for (DdlContent.Column column : table.columns()) {
                bump(columnNames, column.physicalName());
            }
            for (DdlContent.Column column : table.columns()) {
                if (columnNames.getOrDefault(key(column.physicalName()), 0) > 1) {
                    warnings.add(new Warning(Warning.VALIDATION,
                            "컬럼 물리명이 중복입니다: " + table.physicalName() + "." + column.physicalName()));
                }
            }
            for (DdlContent.KeyConstraint unique : table.uniques()) {
                if (keyCounts.getOrDefault(key(unique.name()), 0) > 1) {
                    warnings.add(new Warning(Warning.VALIDATION,
                            "키 이름이 문서 내에서 중복입니다: " + unique.name()));
                }
            }
            for (DdlContent.Index index : table.indexes()) {
                if (keyCounts.getOrDefault(key(index.name()), 0) > 1) {
                    warnings.add(new Warning(Warning.VALIDATION,
                            "키 이름이 문서 내에서 중복입니다: " + index.name()));
                }
            }
        }
        return warnings;
    }

    private static void bump(Map<String, Integer> counts, String name) {
        counts.merge(key(name), 1, Integer::sum);
    }

    private static String key(String name) {
        return name.trim().toLowerCase();
    }

    /* ---------- CREATE TABLE ---------- */

    /** 컬럼 정의 한 줄(들여쓰기 포함, 코멘트 제외) — NOT NULL → DEFAULT(원문) → AI 순서 */
    private static String columnDefinition(DdlContent.Column column, SqlDialect dialect) {
        StringBuilder parts = new StringBuilder("    ").append(column.physicalName())
                .append(' ').append(dialect.columnType(column));
        if (!column.nullable()) {
            parts.append(" NOT NULL");
        }
        // 기본값은 원문 그대로 — 리터럴/표현식 구분이 스키마에 없어 사용자 표현식을 신뢰한다
        if (column.defaultValue() != null) {
            parts.append(" DEFAULT ").append(column.defaultValue());
        }
        String autoIncrement = dialect.autoIncrementInline(column);
        if (autoIncrement != null) {
            parts.append(' ').append(autoIncrement);
        }
        return parts.toString();
    }

    private static String createTableStatement(DdlContent.Table table, SqlDialect dialect) {
        List<String> constraints = new ArrayList<>();
        if (table.primaryKey() != null) {
            String columns = columnNames(table, table.primaryKey().columnIds());
            if (!columns.isEmpty()) {
                constraints.add("    CONSTRAINT " + table.primaryKey().name() + " PRIMARY KEY (" + columns + ")");
            }
        }
        for (DdlContent.KeyConstraint unique : table.uniques()) {
            String columns = columnNames(table, unique.columnIds());
            if (!columns.isEmpty()) {
                constraints.add("    CONSTRAINT " + unique.name() + " UNIQUE (" + columns + ")");
            }
        }

        // 컬럼 정의 + 제약 — 마지막 줄만 쉼표로 닫지 않는다. 줄 주석(`--`)은 쉼표 뒤에 붙여
        // 주석이 쉼표를 삼키지 않게 한다
        List<String> defs = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        for (DdlContent.Column column : table.columns()) {
            defs.add(columnDefinition(column, dialect));
            comments.add(dialect.columnComment(column));
        }
        for (String constraint : constraints) {
            defs.add(constraint);
            comments.add("");
        }

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < defs.size(); i++) {
            String def = defs.get(i);
            String comment = comments.get(i);
            boolean more = i < defs.size() - 1;
            if (i > 0) {
                body.append('\n');
            }
            if (comment.isEmpty()) {
                body.append(def).append(more ? "," : "");
            } else if (comment.stripLeading().startsWith("--")) {
                body.append(def).append(more ? "," : "").append(' ').append(comment.stripLeading());
            } else {
                body.append(def).append(' ').append(comment).append(more ? "," : "");
            }
        }

        String prefix = dialect.createTablePrefix(table);
        return (prefix == null ? "" : prefix + "\n")
                + "CREATE TABLE " + table.physicalName() + " (\n" + body + "\n)"
                + dialect.tableOption(table) + ";";
    }

    /* ---------- FK · 공용 ---------- */

    private static List<String> foreignKeyStatements(DdlContent content) {
        Map<String, DdlContent.Table> tableById = new java.util.HashMap<>();
        for (DdlContent.Table table : content.tables()) {
            if (table.id() != null) {
                tableById.put(table.id(), table);
            }
        }

        List<String> statements = new ArrayList<>();
        for (DdlContent.Relationship rel : content.relationships()) {
            DdlContent.Table child = tableById.get(rel.childTableId());
            DdlContent.Table parent = tableById.get(rel.parentTableId());
            if (child == null || parent == null) {
                continue;
            }
            // 매핑 순서 = 부모 PK 정의 순서(에디터 relationship 빌더 규칙) — FK 컬럼 순서를 그대로 따른다
            List<String> childCols = new ArrayList<>();
            List<String> parentCols = new ArrayList<>();
            for (DdlContent.ColumnMapping mapping : rel.columnMappings()) {
                String childName = columnName(child, mapping.childColumnId());
                String parentName = columnName(parent, mapping.parentColumnId());
                if (childName != null && parentName != null) {
                    childCols.add(childName);
                    parentCols.add(parentName);
                }
            }
            if (childCols.isEmpty()) {
                continue;
            }

            StringBuilder statement = new StringBuilder("ALTER TABLE ").append(child.physicalName())
                    .append(" ADD CONSTRAINT ").append(rel.fkName())
                    .append(" FOREIGN KEY (").append(String.join(", ", childCols)).append(")")
                    .append(" REFERENCES ").append(parent.physicalName())
                    .append(" (").append(String.join(", ", parentCols)).append(")");
            String onDelete = actionSql(rel.onDelete());
            String onUpdate = actionSql(rel.onUpdate());
            if (onDelete != null) {
                statement.append(" ON DELETE ").append(onDelete);
            }
            if (onUpdate != null) {
                statement.append(" ON UPDATE ").append(onUpdate);
            }
            statements.add(statement.append(";").toString());
        }
        return statements;
    }

    private static String actionSql(String action) {
        return action == null ? null : ACTION_SQL.get(action);
    }

    /** 컬럼 물리명 목록 — 없는 id(삭제 cascade 잔여 등)는 건너뛴다 */
    private static String columnNames(DdlContent.Table table, List<String> columnIds) {
        List<String> names = new ArrayList<>();
        for (String columnId : columnIds) {
            String name = columnName(table, columnId);
            if (name != null) {
                names.add(name);
            }
        }
        return String.join(", ", names);
    }

    private static String columnName(DdlContent.Table table, String columnId) {
        for (DdlContent.Column column : table.columns()) {
            if (column.id() != null && column.id().equals(columnId)) {
                return column.physicalName();
            }
        }
        return null;
    }

    private static void addIfNotEmpty(List<String> blocks, List<String> candidate) {
        if (!candidate.isEmpty()) {
            blocks.add(String.join("\n", candidate));
        }
    }
}
