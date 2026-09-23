package net.java21.crowfoot.api.model.sqlimport;

import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DDL 텍스트 → {@link IntrospectedSchema} 파서 (05-editor/04-dbms-engineering.md SQL Import v1).
 *
 * <p>커넥션 리버스와 같은 중립 모델을 만들어 {@code ReverseContentAssembler} 조립을 그대로
 * 재사용한다. 외부 SQL 파서 의존성 없이 자체 토크나이저로 동작하며, <b>관대한 파싱</b>이
 * 원칙이다 — 이해하지 못하는 문장(DROP·INSERT·CREATE INDEX·VIEW…)이나 정의 항목은
 * {@code skipped}에 담고 계속 진행한다. 전체가 실패하는 일은 없다.
 *
 * <p>v1 읽기 범위: CREATE TABLE(컬럼·타입·NOT NULL·DEFAULT·AUTO_INCREMENT·COMMENT,
 * inline PK/UK/FK/REFERENCES) · ALTER TABLE ADD(PK/UK/FK 제약) · COMMENT ON(표준 PG).
 * 일반 인덱스(KEY/INDEX/FULLTEXT)는 읽지 않고 skipped에 남긴다 — 문서의 인덱스 편집은
 * 별도 기능이므로 v1에서는 가져오지 않는다.
 *
 * <p>식별자는 백틱·큰따옴표·대괄호 quoted 형태를 모두 받아 따옴표를 벗긴다. 테이블이
 * 스키마 한정명({@code db.tbl})이면 마지막 조각만 쓴다. REFERENCES에 부모 컬럼 목록이
 * 없으면 모든 문장을 다 읽은 뒤 부모 PK로 채운다.
 */
public class DdlTextParser {

    /** 파서 자체 정규화 — introspector 공용 코드 맵에 없는 방언 별칭 (나머지는 introspector가 정규화) */
    private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
            Map.entry("character varying", "varchar"),
            Map.entry("char varying", "varchar"),
            Map.entry("character", "char"),
            Map.entry("double precision", "double"),
            Map.entry("serial", "int4"),
            Map.entry("bigserial", "int8"),
            Map.entry("smallserial", "int2"),
            Map.entry("serial2", "int2"),
            Map.entry("serial4", "int4"),
            Map.entry("serial8", "int8"));

    /** AUTO_INCREMENT 성격의 타입 — serial 계열 */
    private static final Set<String> AUTO_TYPES = Set.of(
            "serial", "bigserial", "smallserial", "serial2", "serial4", "serial8");

    private static final Set<String> LENGTH_TYPES = Set.of("char", "varchar");

    private static final Set<String> PRECISION_TYPES = Set.of("decimal", "numeric", "dec");

    /** 컬럼 정의가 아니라 테이블 제약으로 시작하는 항목 첫 토큰 */
    private static final Set<String> CONSTRAINT_HEADS = Set.of(
            "primary", "unique", "foreign", "constraint", "key", "index", "fulltext", "spatial",
            "check", "exclude");

    /** 결과 — schema는 조립기 입력, skipped는 읽지 못해 건너뛴 문장·항목 요약 */
    public record DdlParseResult(IntrospectedSchema schema, List<String> skipped) {
    }

    /* ---------- 문장 단위 빌더 ---------- */

    private static final class ColumnBuilder {
        String name;
        String typeName;
        Integer length;
        Integer precision;
        Integer scale;
        boolean nullable = true;
        String defaultValue;
        boolean autoIncrement;
        String comment;
    }

    private static final class UniqueBuilder {
        String name;
        List<String> columns;
    }

    private static final class FkBuilder {
        String name;
        String childTable;
        List<String> childColumns;
        String parentTable;
        List<String> parentColumns; // REFERENCES에 목록이 없으면 null — 부모 PK로 나중에 채운다
        String onDelete;
        String onUpdate;
    }

    private static final class TableBuilder {
        final String name;
        String comment;
        final Map<String, ColumnBuilder> columns = new LinkedHashMap<>();
        String primaryKeyName;
        List<String> primaryKeyColumns;
        final List<UniqueBuilder> uniques = new ArrayList<>();
        final List<FkBuilder> foreignKeys = new ArrayList<>();

        TableBuilder(String name) {
            this.name = name;
        }
    }

    public DdlParseResult parse(String ddl) {
        List<String> skipped = new ArrayList<>();
        Map<String, TableBuilder> tables = new LinkedHashMap<>();
        List<FkBuilder> looseForeignKeys = new ArrayList<>(); // ALTER로 들어왔는데 테이블을 못 찾은 FK

        for (String rawStatement : splitStatements(ddl)) {
            List<Token> tokens = tokenize(rawStatement);
            if (tokens.isEmpty()) {
                continue;
            }
            try {
                parseStatement(tokens, tables, looseForeignKeys, skipped);
            } catch (ParseException e) {
                skipped.add(e.getMessage());
            }
        }
        if (tables.isEmpty()) {
            return new DdlParseResult(new IntrospectedSchema(List.of(), List.of()), List.copyOf(skipped));
        }

        // REFERENCES에 부모 컬럼이 없던 FK — 부모 PK(최후 수단 1:1 대칭)로 채운다
        List<FkBuilder> allForeignKeys = new ArrayList<>();
        for (TableBuilder table : tables.values()) {
            allForeignKeys.addAll(table.foreignKeys);
        }
        allForeignKeys.addAll(looseForeignKeys);
        for (FkBuilder fk : allForeignKeys) {
            if (fk.parentColumns == null) {
                TableBuilder parent = tables.get(fk.parentTable);
                fk.parentColumns = parent != null && parent.primaryKeyColumns != null
                        ? new ArrayList<>(parent.primaryKeyColumns)
                        : List.copyOf(fk.childColumns);
            }
        }

        List<IntrospectedSchema.IntrospectedTable> schemaTables = new ArrayList<>();
        for (TableBuilder table : tables.values()) {
            List<IntrospectedSchema.IntrospectedColumn> columns = new ArrayList<>();
            for (ColumnBuilder column : table.columns.values()) {
                columns.add(new IntrospectedSchema.IntrospectedColumn(
                        column.name, column.typeName, column.length, column.precision, column.scale,
                        column.nullable, column.defaultValue, column.autoIncrement, column.comment));
            }
            List<IntrospectedSchema.IntrospectedUnique> uniques = new ArrayList<>();
            for (UniqueBuilder unique : table.uniques) {
                uniques.add(new IntrospectedSchema.IntrospectedUnique(unique.name, unique.columns));
            }
            schemaTables.add(new IntrospectedSchema.IntrospectedTable(
                    table.name, table.comment, columns, table.primaryKeyName,
                    table.primaryKeyColumns == null ? List.of() : table.primaryKeyColumns, uniques));
        }
        List<IntrospectedSchema.IntrospectedFk> schemaFks = new ArrayList<>();
        for (FkBuilder fk : allForeignKeys) {
            schemaFks.add(new IntrospectedSchema.IntrospectedFk(
                    fk.name, fk.childTable, fk.childColumns, fk.parentTable, fk.parentColumns,
                    fk.onDelete, fk.onUpdate));
        }
        return new DdlParseResult(new IntrospectedSchema(schemaTables, schemaFks), List.copyOf(skipped));
    }

    /* ---------- 문장 판별 ---------- */

    private void parseStatement(List<Token> tokens, Map<String, TableBuilder> tables,
                                List<FkBuilder> looseForeignKeys, List<String> skipped) {
        String head = word(tokens, 0);
        if ("create".equals(head)) {
            parseCreateTable(tokens, tables, skipped);
        } else if ("alter".equals(head)) {
            parseAlterTable(tokens, tables, looseForeignKeys, skipped);
        } else if ("comment".equals(head) && "on".equals(word(tokens, 1))) {
            parseCommentOn(tokens, tables, skipped);
        } else {
            skipped.add(summary(head == null ? "?" : head.toUpperCase(Locale.ROOT), tokens));
        }
    }

    private void parseCreateTable(List<Token> tokens, Map<String, TableBuilder> tables,
                                  List<String> skipped) {
        Cursor c = new Cursor(tokens);
        expectWord(c, "create");
        while ("temporary".equals(c.peekWord(0)) || "unlogged".equals(c.peekWord(0))) {
            c.next();
        }
        String kind = c.nextWord();
        if (!"table".equals(kind)) {
            skipped.add(summary("CREATE " + (kind == null ? "?" : kind.toUpperCase(Locale.ROOT)), tokens));
            return;
        }
        if ("if".equals(c.peekWord(0)) && "not".equals(c.peekWord(1))) {
            c.next();
            c.next();
            c.next(); // IF NOT EXISTS
        }
        String tableName = c.nextQualifiedName();
        if (tableName == null) {
            throw new ParseException(summary("CREATE TABLE (테이블 이름 없음)", tokens));
        }
        if (!"(".equals(c.peekRaw(0))) {
            // CREATE TABLE … AS SELECT — 정의를 읽을 수 없다
            skipped.add(summary("CREATE TABLE " + tableName + " (AS SELECT·컬럼 정의 없음)", tokens));
            return;
        }
        int open = c.index();
        int close = matchingParen(tokens, open);
        TableBuilder table = tables.computeIfAbsent(tableName, TableBuilder::new);
        parseTableBody(table, tokens, open + 1, close, skipped);
        parseTableOptions(table, tokens, close + 1);
    }

    /** CREATE TABLE 괄호 안 — 쉼표로 구분된 컬럼 정의·테이블 제약이 섞여 나온다 */
    private void parseTableBody(TableBuilder table, List<Token> tokens, int from, int to,
                                List<String> skipped) {
        int i = from;
        while (i < to) {
            int end = itemEnd(tokens, i, to);
            List<Token> item = tokens.subList(i, end);
            if (!item.isEmpty()) {
                try {
                    parseTableItem(table, item, skipped);
                } catch (ParseException e) {
                    skipped.add(summary(table.name + " 정의 항목 건너뜀", item));
                }
            }
            i = end + 1; // 항목 끝의 쉼표(또는 to) 건너뛰기
        }
    }

    private void parseTableItem(TableBuilder table, List<Token> item, List<String> skipped) {
        String first = word(item, 0);
        if (first != null && CONSTRAINT_HEADS.contains(first)) {
            parseTableConstraint(table, new Cursor(item), item, skipped);
        } else {
            parseColumnDefinition(table, new Cursor(item), item);
        }
    }

    /** 컬럼 정의 — 이름 타입 [수식] 제약* (모르는 토큰은 건너뛴다 — 관대한 파싱) */
    private void parseColumnDefinition(TableBuilder table, Cursor c, List<Token> item) {
        ColumnBuilder column = new ColumnBuilder();
        column.name = c.nextIdentifier();
        parseColumnType(column, c);
        if (column.typeName == null) {
            column.typeName = "varchar"; // 타입을 못 읽었어도 컬럼은 살린다
        }

        String inlineReferencesParent = null;
        List<String> inlineReferencesColumns = null;
        while (c.hasNext()) {
            String w = c.peekWord(0);
            switch (w == null ? "" : w) {
                case "not" -> {
                    c.next();
                    expectWord(c, "null");
                    column.nullable = false;
                }
                case "null" -> c.next();
                case "default" -> {
                    c.next();
                    column.defaultValue = "(".equals(c.peekRaw(0)) ? c.nextParenRaw() : c.nextLiteral();
                }
                case "auto_increment", "autoincrement" -> {
                    c.next();
                    column.autoIncrement = true;
                }
                case "primary" -> {
                    c.next();
                    expectWord(c, "key");
                    if (table.primaryKeyColumns == null) {
                        table.primaryKeyName = "PRIMARY_" + table.name;
                        table.primaryKeyColumns = new ArrayList<>(List.of(column.name));
                    } else {
                        appendUnique(table.primaryKeyColumns, column.name);
                    }
                    column.nullable = false;
                }
                case "unique" -> {
                    c.next();
                    if ("key".equals(c.peekWord(0)) || "index".equals(c.peekWord(0))) {
                        c.next();
                    }
                    table.uniques.add(uniqueOf("uk_" + table.name + "_" + column.name, List.of(column.name)));
                }
                case "comment" -> {
                    c.next();
                    column.comment = c.nextLiteral();
                }
                case "references" -> {
                    c.next();
                    inlineReferencesParent = c.nextQualifiedName();
                    if (inlineReferencesParent == null) {
                        throw new ParseException(summary("REFERENCES 대상 없음", item));
                    }
                    if ("(".equals(c.peekRaw(0))) {
                        inlineReferencesColumns = c.nextIdentifierList();
                    }
                    consumeReferentialActions(c); // MATCH …·ON DELETE/UPDATE — FK 조립은 아래에서
                }
                case "check", "generated", "as" -> consumeParenOrTail(c);
                case "collate" -> { // COLLATE utf8_bin
                    c.next();
                    c.next();
                }
                case "character" -> { // CHARACTER SET utf8mb4
                    c.next();
                    if ("set".equals(c.peekWord(0))) {
                        c.next();
                    }
                    c.next();
                }
                default -> c.next(); // UNSIGNED·ZEROFILL·ON UPDATE CURRENT_TIMESTAMP 등 — 넘어간다
            }
        }
        table.columns.put(column.name, column);

        if (inlineReferencesParent != null) {
            FkBuilder fk = new FkBuilder();
            fk.name = "fk_" + table.name + "_" + inlineReferencesParent;
            fk.childTable = table.name;
            fk.childColumns = List.of(column.name);
            fk.parentTable = inlineReferencesParent;
            fk.parentColumns = inlineReferencesColumns;
            applyReferentialActions(item, fk);
            table.foreignKeys.add(fk);
        }
    }

    /** 타입 — 별칭 정규화 + (n)·(p,s) 수식. INT(11)처럼 무의미한 길이는 버린다 */
    private void parseColumnType(ColumnBuilder column, Cursor c) {
        String first = c.nextWord();
        if (first == null) {
            return;
        }
        String typeName = first;
        // 두 단어짜리 타입 — double precision, char varying
        String second = c.peekWord(0);
        if (second != null && Set.of("precision", "varying").contains(second)
                && Set.of("double", "character", "char").contains(first)) {
            typeName = first + " " + c.nextWord();
        }
        column.typeName = TYPE_ALIASES.getOrDefault(typeName, typeName);
        if (AUTO_TYPES.contains(typeName)) {
            column.autoIncrement = true;
        }

        if ("(".equals(c.peekRaw(0))) {
            List<String> args = c.nextNumberList();
            if (!args.isEmpty() && isNumber(args.get(0))) {
                if (LENGTH_TYPES.contains(column.typeName)) {
                    column.length = Integer.valueOf(args.get(0));
                } else if (PRECISION_TYPES.contains(column.typeName)) {
                    column.precision = Integer.valueOf(args.get(0));
                    if (args.size() > 1 && isNumber(args.get(1))) {
                        column.scale = Integer.valueOf(args.get(1));
                    }
                }
                // 그 외 타입의 괄호(INT(11)·DATETIME(6))는 표시용 — 버린다
            }
        }
        // TIMESTAMP … WITH TIME ZONE — 부속 절만 소비
        if ("with".equals(c.peekWord(0)) && "time".equals(c.peekWord(1))) {
            c.next();
            c.next();
            if ("zone".equals(c.peekWord(0))) {
                c.next();
            }
        }
    }

    /** 테이블 제약 — [CONSTRAINT [이름]] PRIMARY KEY|UNIQUE|FOREIGN KEY. 그 외 제약은 skipped에 남긴다 */
    private void parseTableConstraint(TableBuilder table, Cursor c, List<Token> item,
                                      List<String> skipped) {
        String constraintName = null;
        if ("constraint".equals(c.peekWord(0))) {
            c.next();
            if (c.peekWord(0) == null || !CONSTRAINT_HEADS.contains(c.peekWord(0))) {
                constraintName = c.nextIdentifier();
            }
        }
        String kind = c.nextWord();
        switch (kind == null ? "" : kind) {
            case "primary" -> {
                expectWord(c, "key");
                List<String> columns = c.nextIdentifierList();
                if (columns.isEmpty()) {
                    throw new ParseException(summary("PRIMARY KEY 컬럼 없음", item));
                }
                if (table.primaryKeyColumns == null) {
                    table.primaryKeyName = constraintName != null ? constraintName : "PRIMARY_" + table.name;
                    table.primaryKeyColumns = new ArrayList<>(columns);
                    for (String name : columns) {
                        ColumnBuilder column = table.columns.get(name);
                        if (column != null) {
                            column.nullable = false;
                        }
                    }
                }
            }
            case "unique" -> {
                if ("key".equals(c.peekWord(0)) || "index".equals(c.peekWord(0))) {
                    c.next();
                }
                String name = constraintName != null ? constraintName : c.nextOptionalIndexName();
                List<String> columns = c.nextIdentifierList();
                if (columns.isEmpty()) {
                    throw new ParseException(summary("UNIQUE 컬럼 없음", item));
                }
                table.uniques.add(uniqueOf(
                        name != null ? name : "uk_" + table.name + "_" + String.join("_", columns), columns));
            }
            case "foreign" -> {
                expectWord(c, "key");
                String name = constraintName != null ? constraintName : c.nextOptionalIndexName();
                List<String> columns = c.nextIdentifierList();
                expectWord(c, "references");
                String parent = c.nextQualifiedName();
                if (columns.isEmpty() || parent == null) {
                    throw new ParseException(summary("FOREIGN KEY 형식 불완전", item));
                }
                FkBuilder fk = new FkBuilder();
                fk.name = name != null ? name : "fk_" + table.name + "_" + parent;
                fk.childTable = table.name;
                fk.childColumns = columns;
                fk.parentTable = parent;
                fk.parentColumns = "(".equals(c.peekRaw(0)) ? c.nextIdentifierList() : null;
                applyReferentialActions(item, fk);
                table.foreignKeys.add(fk);
            }
            default -> skipped.add(summary(table.name + " 제약 건너뜀", item)); // KEY·INDEX·CHECK·FULLTEXT…
        }
    }

    /** CREATE TABLE 닫는 괄호 뒤 — MySQL 테이블 옵션(COMMENT [=] 'x', ENGINE=…) */
    private void parseTableOptions(TableBuilder table, List<Token> tokens, int from) {
        for (int i = from; i < tokens.size() - 1; i++) {
            if (!"comment".equalsIgnoreCase(word(tokens, i))) {
                continue;
            }
            int valueIndex = "=".equals(raw(tokens, i + 1)) ? i + 2 : i + 1;
            if (valueIndex < tokens.size() && tokens.get(valueIndex).isString()) {
                table.comment = tokens.get(valueIndex).text();
            }
        }
    }

    private void parseAlterTable(List<Token> tokens, Map<String, TableBuilder> tables,
                                 List<FkBuilder> looseForeignKeys, List<String> skipped) {
        Cursor c = new Cursor(tokens);
        expectWord(c, "alter");
        expectWord(c, "table");
        String tableName = c.nextQualifiedName();
        if (tableName == null || !"add".equals(c.peekWord(0))) {
            skipped.add(summary("ALTER TABLE (지원하지 않는 형태)", tokens));
            return;
        }
        c.next(); // ADD — 컬럼 추가 등 정의 변경은 읽지 않는다(이미 CREATE에 있다고 본다)
        Cursor item = new Cursor(tokens.subList(c.index(), tokens.size()));

        String constraintName = null;
        if ("constraint".equals(item.peekWord(0))) {
            item.next();
            if (item.peekWord(0) == null || !CONSTRAINT_HEADS.contains(item.peekWord(0))) {
                constraintName = item.nextIdentifier();
            }
        }
        String kind = item.nextWord();
        TableBuilder table = tables.get(tableName);
        switch (kind == null ? "" : kind) {
            case "primary" -> {
                expectWord(item, "key");
                List<String> columns = item.nextIdentifierList();
                if (table != null && table.primaryKeyColumns == null && !columns.isEmpty()) {
                    table.primaryKeyName = constraintName != null ? constraintName : "PRIMARY_" + table.name;
                    table.primaryKeyColumns = new ArrayList<>(columns);
                }
            }
            case "unique" -> {
                if ("key".equals(item.peekWord(0)) || "index".equals(item.peekWord(0))) {
                    item.next();
                }
                String name = constraintName != null ? constraintName : item.nextOptionalIndexName();
                List<String> columns = item.nextIdentifierList();
                if (table != null && !columns.isEmpty()) {
                    table.uniques.add(uniqueOf(
                            name != null ? name : "uk_" + table.name + "_" + String.join("_", columns),
                            columns));
                }
            }
            case "foreign" -> {
                expectWord(item, "key");
                String name = constraintName != null ? constraintName : item.nextOptionalIndexName();
                List<String> columns = item.nextIdentifierList();
                expectWord(item, "references");
                String parent = item.nextQualifiedName();
                if (columns.isEmpty() || parent == null) {
                    skipped.add(summary("ALTER TABLE ADD FOREIGN KEY (형식 불완전)", tokens));
                    return;
                }
                FkBuilder fk = new FkBuilder();
                fk.name = name != null ? name : "fk_" + tableName + "_" + parent;
                fk.childTable = tableName;
                fk.childColumns = columns;
                fk.parentTable = parent;
                fk.parentColumns = "(".equals(item.peekRaw(0)) ? item.nextIdentifierList() : null;
                applyReferentialActions(tokens, fk);
                if (table != null) {
                    table.foreignKeys.add(fk);
                } else {
                    looseForeignKeys.add(fk);
                }
            }
            default -> skipped.add(summary("ALTER TABLE ADD "
                    + (kind == null ? "?" : kind.toUpperCase(Locale.ROOT)) + " (지원하지 않는 추가)", tokens));
        }
    }

    /** COMMENT ON TABLE t IS '…' / COMMENT ON COLUMN t.c IS '…' (표준 PG) */
    private void parseCommentOn(List<Token> tokens, Map<String, TableBuilder> tables,
                                List<String> skipped) {
        Cursor c = new Cursor(tokens);
        c.next(); // COMMENT
        c.next(); // ON
        String target = c.nextWord(); // table | column
        List<String> parts = c.nextNameParts();
        if ("is".equals(c.peekWord(0))) {
            c.next();
        }
        String comment = c.nextLiteral();
        String tableName = parts.isEmpty() ? null : parts.get(0);
        String columnName = parts.size() > 1 ? parts.get(1) : null;
        TableBuilder table = tableName == null ? null : tables.get(tableName);
        if (table == null || comment == null || (columnName == null && !"table".equals(target))) {
            skipped.add(summary("COMMENT ON (대상을 찾을 수 없음)", tokens));
            return;
        }
        if (columnName == null) {
            table.comment = comment;
        } else {
            ColumnBuilder column = table.columns.get(columnName);
            if (column != null) {
                column.comment = comment;
            }
        }
    }

    /* ---------- 공통 조각 ---------- */

    /** 문장 토큰에서 ON DELETE/ON UPDATE 절을 찾아 FK에 반영한다 (MySQL 컬럼 속성 ON UPDATE CURRENT_TIMESTAMP는 무시된다) */
    private static void applyReferentialActions(List<Token> item, FkBuilder fk) {
        for (int i = 0; i < item.size() - 1; i++) {
            if (!"on".equals(word(item, i))) {
                continue;
            }
            String which = word(item, i + 1);
            String action = referentialAction(item, i + 2);
            if (action == null) {
                continue;
            }
            if ("delete".equals(which)) {
                fk.onDelete = action;
            } else if ("update".equals(which)) {
                fk.onUpdate = action;
            }
        }
    }

    private static String referentialAction(List<Token> tokens, int index) {
        String first = word(tokens, index);
        if (first == null) {
            return null;
        }
        if ("set".equals(first)) {
            String second = word(tokens, index + 1);
            if ("null".equals(second)) {
                return "SET NULL";
            }
            return "default".equals(second) ? "SET DEFAULT" : null;
        }
        if ("cascade".equals(first) || "restrict".equals(first)) {
            return first.toUpperCase(Locale.ROOT);
        }
        if ("no".equals(first)) {
            return "NO ACTION";
        }
        return null;
    }

    /** 커서 위치의 ON DELETE/UPDATE·MATCH 절을 소비한다 (inline REFERENCES 경로) */
    private static void consumeReferentialActions(Cursor c) {
        while ("on".equals(c.peekWord(0))
                && ("delete".equals(c.peekWord(1)) || "update".equals(c.peekWord(1)))) {
            c.next();
            c.next();
            if ("set".equals(c.peekWord(0))) {
                c.next();
                c.next();
            } else {
                c.next();
                if ("action".equals(c.peekWord(0))) {
                    c.next();
                }
            }
        }
        if ("match".equals(c.peekWord(0))) {
            c.next();
            c.next();
        }
    }

    private static UniqueBuilder uniqueOf(String name, List<String> columns) {
        UniqueBuilder unique = new UniqueBuilder();
        unique.name = name;
        unique.columns = columns;
        return unique;
    }

    private static void appendUnique(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static boolean isNumber(String value) {
        return !value.isEmpty() && value.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }

    private static void expectWord(Cursor c, String expected) {
        String actual = c.nextWord();
        if (!expected.equals(actual == null ? "" : actual)) {
            throw new ParseException("예상 토큰 불일치: " + expected);
        }
    }

    /** 문장 요약 — skipped 목록에 사람이 읽을 수 있게 (문자열 리터럴 제외·최대 6토큰).
     *  문장 원문 토큰으로 렌더한다 — head 라벨은 첫 토큰과 중복되므로 토큰이 없을 때만 폴백. */
    private static String summary(String head, List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Token token : tokens) {
            if (token.isString()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token.raw());
            if (++count >= 6) {
                sb.append(" …");
                break;
            }
        }
        return sb.length() > 0 ? sb.toString() : head;
    }

    private static int matchingParen(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            String raw = tokens.get(i).raw();
            if ("(".equals(raw)) {
                depth++;
            } else if (")".equals(raw)) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new ParseException("괄호가 닫히지 않았습니다");
    }

    /** 최상위 쉼표 지점 — 테이블 정의 항목(컬럼·제약) 경계. 괄호 안의 쉼표는 무시한다 */
    private static int itemEnd(List<Token> tokens, int from, int to) {
        int depth = 0;
        for (int i = from; i < to; i++) {
            String raw = tokens.get(i).raw();
            if ("(".equals(raw)) {
                depth++;
            } else if (")".equals(raw)) {
                depth--;
            } else if (",".equals(raw) && depth == 0) {
                return i;
            }
        }
        return to;
    }

    private static String word(List<Token> tokens, int index) {
        if (index < 0 || index >= tokens.size()) {
            return null;
        }
        Token token = tokens.get(index);
        return token.isWord() ? token.text().toLowerCase(Locale.ROOT) : null;
    }

    private static String raw(List<Token> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index).raw() : null;
    }

    /** CHECK(…)·GENERATED … AS (…) — 괄호를 소진해 버린다(컬럼 자체는 유지) */
    private static void consumeParenOrTail(Cursor c) {
        while (c.hasNext() && !"(".equals(c.peekRaw(0))) {
            c.next();
        }
        if ("(".equals(c.peekRaw(0))) {
            c.nextParenRaw();
        }
    }

    /* ---------- 전처리·토크나이저 ---------- */

    /** 주석 제거 후 세미콜론으로 문장 분리 (따옴표 안은 보존) */
    private static List<String> splitStatements(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < ddl.length(); i++) {
            char ch = ddl.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (quote == '\'' && ch == '\\') {
                    if (i + 1 < ddl.length()) {
                        current.append(ddl.charAt(++i));
                    }
                    continue;
                }
                if (ch == quote) {
                    // '' `` "" 이스케이프
                    if (i + 1 < ddl.length() && ddl.charAt(i + 1) == quote) {
                        current.append(ddl.charAt(++i));
                        continue;
                    }
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                current.append(ch);
                continue;
            }
            if (ch == '-' && i + 1 < ddl.length() && ddl.charAt(i + 1) == '-') {
                i = skipToLineEnd(ddl, i);
                current.append(' ');
                continue;
            }
            if (ch == '#') {
                i = skipToLineEnd(ddl, i);
                current.append(' ');
                continue;
            }
            if (ch == '/' && i + 1 < ddl.length() && ddl.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < ddl.length() && !(ddl.charAt(i) == '*' && ddl.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                current.append(' ');
                continue;
            }
            if (ch == ';') {
                if (!current.toString().isBlank()) {
                    statements.add(current.toString());
                }
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private static int skipToLineEnd(String ddl, int i) {
        while (i < ddl.length() && ddl.charAt(i) != '\n') {
            i++;
        }
        return i - 1; // for 문의 i++와 맞춘다 — '\n'은 그대로 남긴다
    }

    private sealed interface Token permits WordToken, StringToken, QuotedToken, PunctToken {
        String text();

        String raw();

        boolean isWord();

        boolean isString();

        boolean isIdentifier();
    }

    private record WordToken(String text, String raw) implements Token {
        WordToken(String word) {
            this(word.toLowerCase(Locale.ROOT), word);
        }

        @Override
        public boolean isWord() {
            return true;
        }

        @Override
        public boolean isString() {
            return false;
        }

        @Override
        public boolean isIdentifier() {
            return true;
        }
    }

    private record StringToken(String text, String raw) implements Token {
        @Override
        public boolean isWord() {
            return false;
        }

        @Override
        public boolean isString() {
            return true;
        }

        @Override
        public boolean isIdentifier() {
            return true;
        }
    }

    private record QuotedToken(String text, String raw) implements Token {
        @Override
        public boolean isWord() {
            return false;
        }

        @Override
        public boolean isString() {
            return false;
        }

        @Override
        public boolean isIdentifier() {
            return true;
        }
    }

    private record PunctToken(String text, String raw) implements Token {
        PunctToken(String raw) {
            this(raw, raw);
        }

        @Override
        public boolean isWord() {
            return false;
        }

        @Override
        public boolean isString() {
            return false;
        }

        @Override
        public boolean isIdentifier() {
            return false;
        }
    }

    /** 식별자·키워드·문자열·기호 토큰화 — 백틱·큰따옴표·대괄호 quoted 식별자와 '…' 문자열 보존 */
    private static List<Token> tokenize(String statement) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < statement.length()) {
            char ch = statement.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                int start = i;
                StringBuilder value = new StringBuilder();
                i++;
                while (i < statement.length()) {
                    char inner = statement.charAt(i);
                    if (inner == '\\' && ch == '\'') {
                        if (i + 1 < statement.length()) {
                            value.append(statement.charAt(++i));
                        }
                        i++;
                        continue;
                    }
                    if (inner == ch) {
                        if (i + 1 < statement.length() && statement.charAt(i + 1) == ch) {
                            value.append(ch);
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    value.append(inner);
                    i++;
                }
                String raw = statement.substring(start, Math.min(i, statement.length()));
                tokens.add(ch == '\'' ? new StringToken(value.toString(), raw) : new QuotedToken(value.toString(), raw));
                continue;
            }
            if (ch == '[') {
                int close = statement.indexOf(']', i);
                if (close > i) {
                    tokens.add(new QuotedToken(statement.substring(i + 1, close), statement.substring(i, close + 1)));
                    i = close + 1;
                    continue;
                }
            }
            if (Character.isLetter(ch) || ch == '_' || ch == '$') {
                int start = i;
                while (i < statement.length() && (Character.isLetterOrDigit(statement.charAt(i))
                        || statement.charAt(i) == '_' || statement.charAt(i) == '$')) {
                    i++;
                }
                tokens.add(new WordToken(statement.substring(start, i)));
                continue;
            }
            if (Character.isDigit(ch) || (ch == '-' && i + 1 < statement.length()
                    && Character.isDigit(statement.charAt(i + 1)))) {
                int start = i;
                while (i < statement.length() && (Character.isDigit(statement.charAt(i)) || statement.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new PunctToken(statement.substring(start, i)));
                continue;
            }
            tokens.add(new PunctToken(String.valueOf(ch)));
            i++;
        }
        return tokens;
    }

    /** 토큰 순회 — 식별자 목록·qualified name 같은 반복 패턴을 메서드로 제공한다 */
    private static final class Cursor {
        private final List<Token> tokens;
        private int index;

        Cursor(List<Token> tokens) {
            this.tokens = tokens;
        }

        int index() {
            return index;
        }

        boolean hasNext() {
            return index < tokens.size();
        }

        Token next() {
            return tokens.get(index++);
        }

        String peekWord(int offset) {
            Token token = peek(offset);
            return token != null && token.isWord() ? token.text().toLowerCase(Locale.ROOT) : null;
        }

        String peekRaw(int offset) {
            Token token = peek(offset);
            return token == null ? null : token.raw();
        }

        String peekIdentifier(int offset) {
            Token token = peek(offset);
            return token != null && token.isIdentifier() && !"(".equals(token.raw()) ? token.text() : null;
        }

        /** 제약의 인덱스명(있으면) — 이름을 얻었으면 소비한다. UNIQUE (a,b)처럼 바로 괄호면 null */
        String nextOptionalIndexName() {
            String name = peekIdentifier(0);
            if (name != null) {
                next();
            }
            return name;
        }

        private Token peek(int offset) {
            int at = index + offset;
            return at >= 0 && at < tokens.size() ? tokens.get(at) : null;
        }

        String nextWord() {
            Token token = hasNext() ? next() : null;
            return token != null && token.isWord() ? token.text().toLowerCase(Locale.ROOT) : null;
        }

        String nextIdentifier() {
            Token token = hasNext() ? next() : null;
            if (token == null || !token.isIdentifier()) {
                throw new ParseException("식별자가 필요합니다");
            }
            return token.text();
        }

        /** ident (. ident)* — COMMENT ON COLUMN t.c · db.tbl 같은 연결 이름의 조각 목록 */
        List<String> nextNameParts() {
            List<String> parts = new ArrayList<>();
            if (!hasNext()) {
                return parts;
            }
            parts.add(nextIdentifier());
            while (".".equals(peekRaw(0)) && peekIdentifier(1) != null) {
                next();
                parts.add(nextIdentifier());
            }
            return parts;
        }

        /** 스키마 한정명 — 마지막 조각만 쓴다 (db.tbl → tbl) */
        String nextQualifiedName() {
            List<String> parts = nextNameParts();
            return parts.isEmpty() ? null : parts.get(parts.size() - 1);
        }

        /** 문자열 리터럴 값 — 문자열이 아니면 원문 토큰 */
        String nextLiteral() {
            return hasNext() ? next().text() : null;
        }

        /** ( … ) 괄호 전체를 원문으로 되돌려준다 — DEFAULT (expr) */
        String nextParenRaw() {
            if (!"(".equals(peekRaw(0))) {
                return null;
            }
            StringBuilder sb = new StringBuilder(next().raw());
            int depth = 1;
            while (hasNext() && depth > 0) {
                Token token = next();
                if ("(".equals(token.raw())) {
                    depth++;
                } else if (")".equals(token.raw())) {
                    depth--;
                }
                if (depth > 0) {
                    sb.append(' ').append(token.raw());
                }
            }
            return sb.append(')').toString();
        }

        /** ( a, b, c ) — 여는 괄호부터 닫는 괄호까지 식별자 목록 */
        List<String> nextIdentifierList() {
            if (!"(".equals(peekRaw(0))) {
                return List.of();
            }
            next();
            List<String> names = new ArrayList<>();
            while (hasNext()) {
                Token token = next();
                if (")".equals(token.raw())) {
                    break;
                }
                if (",".equals(token.raw())) {
                    continue;
                }
                if (token.isIdentifier()) {
                    names.add(token.text());
                }
            }
            return names;
        }

        /** ( n ) · ( p, s ) — 타입 수식 인자 목록 */
        List<String> nextNumberList() {
            if (!"(".equals(peekRaw(0))) {
                return List.of();
            }
            next();
            List<String> values = new ArrayList<>();
            while (hasNext()) {
                Token token = next();
                if (")".equals(token.raw())) {
                    break;
                }
                if (!",".equals(token.raw())) {
                    values.add(token.text());
                }
            }
            return values;
        }
    }

    static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }
}
