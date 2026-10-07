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
 * <p>읽기 범위: CREATE TABLE(컬럼·타입·NOT NULL·DEFAULT·AUTO_INCREMENT·COMMENT·ON UPDATE·생성식·CHECK,
 * inline PK/UK/FK/REFERENCES·KEY/INDEX/FULLTEXT/SPATIAL) · CREATE [UNIQUE|FULLTEXT|SPATIAL] INDEX ·
 * ALTER TABLE ADD(PK/UK/FK/INDEX/CHECK) · COMMENT ON(표준 PG). 읽었지만 줄이거나 버린 것(타입 축소,
 * UNSIGNED·COLLATE 같은 컬럼 속성, 인덱스 접두 길이)은 {@code warnings}에 남긴다(v1.34).
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

    private static final Set<String> LENGTH_TYPES = Set.of("char", "varchar", "binary", "varbinary");

    /** 소수 초 자릿수를 받는 타입 — DATETIME(6) */
    private static final Set<String> FRACTIONAL_TYPES = Set.of("time", "datetime", "timestamp", "timestamptz");

    /** 세션·도구 문장 — 스키마가 아니라 읽지 않는다고 따로 알린다 */
    private static final Set<String> SESSION_HEADS = Set.of("set", "use", "begin", "commit", "start", "delimiter");

    private static final Set<String> PRECISION_TYPES = Set.of("decimal", "numeric", "dec");

    /** 컬럼 정의가 아니라 테이블 제약으로 시작하는 항목 첫 토큰 */
    private static final Set<String> CONSTRAINT_HEADS = Set.of(
            "primary", "unique", "foreign", "constraint", "key", "index", "fulltext", "spatial",
            "check", "exclude");

    /** 결과 — schema는 조립기 입력, skipped는 읽지 못해 건너뛴 문장·항목 요약,
     *  warnings는 읽었지만 줄이거나 버린 것 */
    public record DdlParseResult(IntrospectedSchema schema, List<String> skipped, List<String> warnings) {
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
        boolean identityAlways;
        String comment;
        String generatedExpression;
        boolean generatedStored;
        String onUpdate;
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
        final List<IntrospectedSchema.IntrospectedIndex> indexes = new ArrayList<>();
        final List<IntrospectedSchema.IntrospectedCheck> checks = new ArrayList<>();
        final List<String> warnings;

        TableBuilder(String name, List<String> warnings) {
            this.name = name;
            this.warnings = warnings;
        }

        /** 이름 없는 테이블 CHECK의 이름 — MySQL이 붙이는 이름({테이블}_chk_{n})과 같게 */
        String nextCheckName() {
            return name + "_chk_" + (checks.size() + 1);
        }
    }

    /** 인덱스 키 조각 — 컬럼명·정렬·접두 길이(MySQL col(10), 문서는 표현하지 못해 버린다)·연산자 클래스(v1.37).
     *  name이 null이면 식 조각이다. raw는 조각 원문, ignored는 읽고 버린 수식(COLLATE·NULLS LAST) */
    private record KeyPart(String name, String order, String prefixLength, String opclass, String raw, String ignored) {
    }

    /** 인덱스 접근 방법 — USING 뒤 이름 → 문서의 인덱스 종류 */
    private static final Map<String, String> INDEX_METHODS = Map.of(
            "btree", "BTREE", "hash", "HASH", "gin", "GIN", "gist", "GIST", "brin", "BRIN", "spgist", "SPGIST");

    /** 키 조각 해석 결과 — 컬럼 목록 또는 식 원문(식 조각이 하나라도 있으면 목록 전체를 원문으로 담는다) */
    private record IndexKeys(List<IntrospectedSchema.IndexColumn> columns, String expression) {

        boolean plainColumns() {
            return expression == null && columns.stream().allMatch(column -> column.opclass() == null);
        }

        List<String> names() {
            return columns.stream().map(IntrospectedSchema.IndexColumn::name).toList();
        }
    }

    public DdlParseResult parse(String ddl) {
        List<String> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, TableBuilder> tables = new LinkedHashMap<>();
        List<FkBuilder> looseForeignKeys = new ArrayList<>(); // ALTER로 들어왔는데 테이블을 못 찾은 FK

        for (String rawStatement : splitStatements(ddl)) {
            List<Token> tokens = tokenize(rawStatement);
            if (tokens.isEmpty()) {
                continue;
            }
            try {
                parseStatement(tokens, tables, looseForeignKeys, skipped, warnings);
            } catch (ParseException e) {
                skipped.add(e.getMessage());
            }
        }
        if (tables.isEmpty()) {
            return new DdlParseResult(new IntrospectedSchema(List.of(), List.of()), List.copyOf(skipped),
                    List.copyOf(warnings));
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
                        column.nullable, column.defaultValue, column.autoIncrement, column.comment,
                        column.generatedExpression, column.generatedStored, column.onUpdate, column.identityAlways));
            }
            List<IntrospectedSchema.IntrospectedUnique> uniques = new ArrayList<>();
            for (UniqueBuilder unique : table.uniques) {
                uniques.add(new IntrospectedSchema.IntrospectedUnique(unique.name, unique.columns));
            }
            schemaTables.add(new IntrospectedSchema.IntrospectedTable(
                    table.name, table.comment, columns, table.primaryKeyName,
                    table.primaryKeyColumns == null ? List.of() : table.primaryKeyColumns, uniques,
                    List.copyOf(table.indexes), List.copyOf(table.checks)));
        }
        List<IntrospectedSchema.IntrospectedFk> schemaFks = new ArrayList<>();
        for (FkBuilder fk : allForeignKeys) {
            schemaFks.add(new IntrospectedSchema.IntrospectedFk(
                    fk.name, fk.childTable, fk.childColumns, fk.parentTable, fk.parentColumns,
                    fk.onDelete, fk.onUpdate));
        }
        return new DdlParseResult(new IntrospectedSchema(schemaTables, schemaFks), List.copyOf(skipped),
                List.copyOf(warnings));
    }

    /* ---------- 문장 판별 ---------- */

    private void parseStatement(List<Token> tokens, Map<String, TableBuilder> tables,
                                List<FkBuilder> looseForeignKeys, List<String> skipped, List<String> warnings) {
        String head = word(tokens, 0);
        if ("create".equals(head)) {
            parseCreateTable(tokens, tables, skipped, warnings);
        } else if ("alter".equals(head)) {
            parseAlterTable(tokens, tables, looseForeignKeys, skipped);
        } else if ("comment".equals(head) && "on".equals(word(tokens, 1))) {
            parseCommentOn(tokens, tables, skipped);
        } else if (head != null && SESSION_HEADS.contains(head)) {
            skipped.add("세션 문장 — 읽지 않음: " + summary(head.toUpperCase(Locale.ROOT), tokens));
        } else {
            skipped.add(summary(head == null ? "?" : head.toUpperCase(Locale.ROOT), tokens));
        }
    }

    private void parseCreateTable(List<Token> tokens, Map<String, TableBuilder> tables,
                                  List<String> skipped, List<String> warnings) {
        Cursor c = new Cursor(tokens);
        expectWord(c, "create");
        while ("temporary".equals(c.peekWord(0)) || "unlogged".equals(c.peekWord(0))) {
            c.next();
        }
        String kind = c.nextWord();
        if ("index".equals(kind) || "unique".equals(kind) || "fulltext".equals(kind) || "spatial".equals(kind)) {
            parseCreateIndex(kind, c, tokens, tables, skipped);
            return;
        }
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
        TableBuilder table = tables.computeIfAbsent(tableName, name -> new TableBuilder(name, warnings));
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
        parseColumnType(table, column, c);
        if (column.typeName == null) {
            column.typeName = "varchar"; // 타입을 못 읽었어도 컬럼은 살린다
        }
        String target = table.name + "." + column.name;

        String inlineReferencesParent = null;
        List<String> inlineReferencesColumns = null;
        String pendingConstraintName = null;
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
                    column.defaultValue = c.nextValueExpression();
                }
                case "on" -> {
                    // ON UPDATE CURRENT_TIMESTAMP(6) — MySQL 컬럼 속성
                    c.next();
                    if ("update".equals(c.peekWord(0))) {
                        c.next();
                        column.onUpdate = c.nextValueExpression();
                    }
                }
                case "auto_increment", "autoincrement" -> {
                    c.next();
                    column.autoIncrement = true;
                }
                case "primary" -> {
                    c.next();
                    expectWord(c, "key");
                    if (table.primaryKeyColumns == null) {
                        table.primaryKeyName = defaultPrimaryKeyName(table.name);
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
                    table.uniques.add(uniqueOf(pendingConstraintName != null ? pendingConstraintName
                            : "uk_" + table.name + "_" + column.name, List.of(column.name)));
                    pendingConstraintName = null;
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
                case "constraint" -> { // 컬럼 안의 이름 붙은 제약 — CONSTRAINT ck_x CHECK (…)
                    c.next();
                    if (c.peekWord(0) == null || !Set.of("check", "unique", "primary", "references").contains(c.peekWord(0))) {
                        pendingConstraintName = c.nextIdentifier();
                    }
                }
                case "check" -> {
                    c.next();
                    String expression = c.nextParenExpression();
                    if (expression != null) {
                        // 이름 없는 컬럼 CHECK — PostgreSQL이 붙이는 이름({테이블}_{컬럼}_check)과 같게
                        table.checks.add(new IntrospectedSchema.IntrospectedCheck(pendingConstraintName != null
                                ? pendingConstraintName : table.name + "_" + column.name + "_check", expression));
                    }
                    pendingConstraintName = null;
                    skipNotEnforced(table, c, target);
                }
                case "generated" -> { // GENERATED ALWAYS AS (식) [VIRTUAL|STORED] — IDENTITY는 자동 증가
                    c.next();
                    boolean always = "always".equals(c.peekWord(0));
                    if (always || "by".equals(c.peekWord(0))) {
                        c.next();
                        if ("default".equals(c.peekWord(0))) {
                            c.next();
                        }
                    }
                    if ("as".equals(c.peekWord(0)) && "identity".equals(c.peekWord(1))) {
                        c.next();
                        c.next();
                        column.autoIncrement = true;
                        column.identityAlways = always; // ALWAYS와 BY DEFAULT를 가른다(신고 44)
                        if ("(".equals(c.peekRaw(0))) {
                            c.nextParenRaw(); // 시퀀스 옵션
                        }
                    } else {
                        parseGenerated(column, c);
                    }
                }
                case "as" -> parseGenerated(column, c);
                case "collate" -> { // COLLATE utf8_bin
                    c.next();
                    Token value = c.hasNext() ? c.next() : null;
                    table.warnings.add(target + ": COLLATE " + (value == null ? "" : value.text()) + " — 문서에 담지 않음");
                }
                case "character", "charset" -> { // CHARACTER SET utf8mb4
                    c.next();
                    if ("set".equals(c.peekWord(0))) {
                        c.next();
                    }
                    Token value = c.hasNext() ? c.next() : null;
                    table.warnings.add(target + ": CHARACTER SET " + (value == null ? "" : value.text()) + " — 문서에 담지 않음");
                }
                case "unsigned", "zerofill" -> {
                    c.next();
                    table.warnings.add(target + ": " + w.toUpperCase(Locale.ROOT) + " — 문서에 담지 않음");
                }
                case "visible", "invisible", "stored", "virtual", "persistent" -> c.next();
                default -> {
                    Token skippedToken = c.next();
                    if (skippedToken.isWord()) {
                        table.warnings.add(target + ": " + skippedToken.raw() + " — 읽지 못한 컬럼 속성");
                    }
                }
            }
        }
        if (column.generatedExpression != null) {
            // 생성 컬럼에는 기본값·자동 증가·ON UPDATE가 없다
            column.defaultValue = null;
            column.autoIncrement = false;
            column.onUpdate = null;
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

    /** [GENERATED ALWAYS] AS (식) [VIRTUAL|STORED|PERSISTENT] — 저장 방식이 없으면 MySQL 기본값 VIRTUAL */
    private static void parseGenerated(ColumnBuilder column, Cursor c) {
        if ("as".equals(c.peekWord(0))) {
            c.next();
        }
        String expression = c.nextParenExpression();
        if (expression == null) {
            return;
        }
        column.generatedExpression = expression;
        String storage = c.peekWord(0);
        column.generatedStored = "stored".equals(storage) || "persistent".equals(storage);
        if ("stored".equals(storage) || "virtual".equals(storage) || "persistent".equals(storage)) {
            c.next();
        }
    }

    /** CHECK (…) NOT ENFORCED — 문서는 강제 여부를 담지 않는다 */
    private static void skipNotEnforced(TableBuilder table, Cursor c, String target) {
        if ("not".equals(c.peekWord(0)) && "enforced".equals(c.peekWord(1))) {
            c.next();
            c.next();
            table.warnings.add(target + ": CHECK NOT ENFORCED — 강제 여부를 문서에 담지 않음");
        } else if ("enforced".equals(c.peekWord(0))) {
            c.next();
        }
    }

    /** 가져오기가 정하는 PK 이름 — 에디터 기본 이름과 같은 {테이블}_pk (05-editor/01-core.md §4) */
    static String defaultPrimaryKeyName(String tableName) {
        return tableName.toLowerCase(Locale.ROOT) + "_pk";
    }

    /** 타입 — 별칭 정규화 + (n)·(p,s)·소수 초(n) 수식. INT(11)처럼 무의미한 길이는 버린다 */
    private void parseColumnType(TableBuilder table, ColumnBuilder column, Cursor c) {
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
                } else if (FRACTIONAL_TYPES.contains(column.typeName)) {
                    column.precision = Integer.valueOf(args.get(0));
                }
                // 그 외 타입의 괄호(INT(11) 표시 폭)는 의미가 없어 버린다
            } else if (!args.isEmpty()) {
                // ENUM('A','B')·SET(…) — 문서 타입으로 담지 못한다
                table.warnings.add(table.name + "." + column.name + ": " + typeName.toUpperCase(Locale.ROOT)
                        + "(…) 값 목록 — 문서에 담지 않음");
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

    /** 테이블 제약 — [CONSTRAINT [이름]] PRIMARY KEY|UNIQUE|FOREIGN KEY|CHECK, KEY|INDEX|FULLTEXT|SPATIAL 인덱스 */
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
                List<String> columns = keyPartNames(table, c.nextKeyParts(), item);
                if (columns.isEmpty()) {
                    throw new ParseException(summary("PRIMARY KEY 컬럼 없음", item));
                }
                if (table.primaryKeyColumns == null) {
                    table.primaryKeyName = constraintName != null ? constraintName : defaultPrimaryKeyName(table.name);
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
                String method = readUsing(table, name, c);
                IndexKeys keys = indexKeys(table, name, c.nextKeyParts());
                if (keys.columns().isEmpty() && keys.expression() == null) {
                    throw new ParseException(summary("UNIQUE 컬럼 없음", item));
                }
                if (!keys.plainColumns()) {
                    // MySQL 함수 키 조각 유니크 — 유니크 인덱스로 담는다(v1.37)
                    putIndex(table, name, method != null ? method : "BTREE", null, keys, true, null, List.of());
                    return;
                }
                List<String> columns = keys.names();
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
            case "check" -> {
                String expression = c.nextParenExpression();
                if (expression == null) {
                    throw new ParseException(summary("CHECK 식 없음", item));
                }
                table.checks.add(new IntrospectedSchema.IntrospectedCheck(
                        constraintName != null ? constraintName : table.nextCheckName(), expression));
                skipNotEnforced(table, c, table.name);
            }
            case "key", "index" -> addIndex(table, "BTREE", constraintName, c, item);
            case "fulltext", "spatial" -> {
                if ("key".equals(c.peekWord(0)) || "index".equals(c.peekWord(0))) {
                    c.next();
                }
                addIndex(table, kind.toUpperCase(Locale.ROOT), constraintName, c, item);
            }
            default -> skipped.add(summary(table.name + " 제약 건너뜀", item)); // EXCLUDE…
        }
    }

    /** 인덱스 — [이름] [USING 방식] (키 조각) [USING 방식] [WITH PARSER p] [COMMENT '…'] [VISIBLE] */
    private void addIndex(TableBuilder table, String type, String name, Cursor c, List<Token> item) {
        String indexName = name != null ? name : c.nextOptionalIndexName();
        String method = readUsing(table, indexName, c);
        IndexKeys keys = indexKeys(table, indexName, c.nextKeyParts());
        if (keys.columns().isEmpty() && keys.expression() == null) {
            throw new ParseException(summary("인덱스 컬럼 없음", item));
        }
        String parser = null;
        while (c.hasNext()) {
            String w = c.peekWord(0);
            if ("using".equals(w)) {
                String after = readUsing(table, indexName, c);
                method = after != null ? after : method;
            } else if ("with".equals(w) && "parser".equals(c.peekWord(1))) {
                c.next();
                c.next();
                parser = c.nextIdentifier();
            } else if ("comment".equals(w)) {
                c.next();
                c.next();
            } else {
                c.next(); // VISIBLE·KEY_BLOCK_SIZE …
            }
        }
        putIndex(table, indexName, "BTREE".equals(type) && method != null ? method : type, parser, keys, false, null, List.of());
    }

    private static void putIndex(TableBuilder table, String indexName, String type, String parser, IndexKeys keys,
                                 boolean unique, String where, List<String> include) {
        String name = indexName != null ? indexName : (unique ? "uk_" : "idx_") + table.name + "_"
                + (keys.expression() != null ? "expr" : String.join("_", keys.names()));
        table.indexes.add(new IntrospectedSchema.IntrospectedIndex(name, keys.columns(), type, parser,
                unique, keys.expression(), where, include));
    }

    /** USING 방식 — 문서의 인덱스 종류로. 모르는 방식은 BTREE로 읽고 경고한다. USING이 없으면 null */
    private static String readUsing(TableBuilder table, String indexName, Cursor c) {
        if (!"using".equals(c.peekWord(0))) {
            return null;
        }
        c.next();
        if (!c.hasNext() || "(".equals(c.peekRaw(0))) {
            return null;
        }
        String method = c.nextWord();
        String type = method == null ? null : INDEX_METHODS.get(method);
        if (type == null) {
            table.warnings.add(table.name + "." + (indexName == null ? "?" : indexName) + ": USING "
                    + (method == null ? "?" : method.toUpperCase(Locale.ROOT)) + " — BTREE 인덱스로 읽음");
            return "BTREE";
        }
        return type;
    }

    /** 키 조각에서 컬럼 이름만(기본 키) — 접두 길이는 문서가 담지 못해 경고로 남긴다. 식 조각이 있으면 읽지 못한다 */
    private static List<String> keyPartNames(TableBuilder table, List<KeyPart> parts, List<Token> item) {
        List<String> names = new ArrayList<>();
        for (KeyPart part : parts) {
            if (part.name() == null) {
                throw new ParseException(summary(table.name + " 식 키 — 컬럼으로 표현할 수 없음", item));
            }
            warnKeyPart(table, part);
            names.add(part.name());
        }
        return names;
    }

    private static void warnKeyPart(TableBuilder table, KeyPart part) {
        if (part.prefixLength() != null) {
            table.warnings.add(table.name + "." + part.name() + ": 인덱스 접두 길이(" + part.prefixLength()
                    + ") — 문서에 담지 않음");
        }
        if (part.ignored() != null) {
            table.warnings.add(table.name + "." + part.name() + ": 인덱스 키의 " + part.ignored() + " — 문서에 담지 않음");
        }
    }

    /** 인덱스 키 — 컬럼 조각만이면 컬럼 목록(연산자 클래스 포함), 식 조각이 있으면 목록 전체를 원문으로 담는다(v1.37) */
    private static IndexKeys indexKeys(TableBuilder table, String indexName, List<KeyPart> parts) {
        if (parts.stream().anyMatch(part -> part.name() == null)) {
            return new IndexKeys(List.of(), String.join(", ", parts.stream().map(KeyPart::raw).toList()));
        }
        List<IntrospectedSchema.IndexColumn> columns = new ArrayList<>();
        for (KeyPart part : parts) {
            warnKeyPart(table, part);
            columns.add(new IntrospectedSchema.IndexColumn(part.name(), part.order(), part.opclass()));
        }
        return new IndexKeys(columns, null);
    }

    /** CREATE [UNIQUE|FULLTEXT|SPATIAL] INDEX [CONCURRENTLY] [IF NOT EXISTS] 이름 ON [ONLY] 테이블 [USING 방식] (키 조각)
     *  [INCLUDE (…)] [NULLS [NOT] DISTINCT] [WITH (…)] [TABLESPACE t] [WHERE 조건] — kind는 CREATE 다음 단어.
     *  대상 테이블이 앞에서 만들어져 있어야 한다. 컬럼만으로 된 유니크 인덱스는 유니크 키로, 조건·식·INCLUDE·방법·
     *  연산자 클래스가 붙은 유니크 인덱스는 유니크 인덱스로 담는다(뜻이 바뀌지 않게 — 신고 44) */
    private void parseCreateIndex(String kind, Cursor c, List<Token> tokens, Map<String, TableBuilder> tables,
                                  List<String> skipped) {
        String type = "BTREE";
        boolean unique = false;
        if (!"index".equals(kind)) {
            if ("unique".equals(kind)) {
                unique = true;
            } else {
                type = kind.toUpperCase(Locale.ROOT);
            }
            expectWord(c, "index");
        }
        if ("concurrently".equals(c.peekWord(0))) {
            c.next();
        }
        if ("if".equals(c.peekWord(0)) && "not".equals(c.peekWord(1))) {
            c.next();
            c.next();
            c.next();
        }
        String indexName = "on".equals(c.peekWord(0)) ? null : c.nextQualifiedName();
        expectWord(c, "on");
        if ("only".equals(c.peekWord(0))) {
            c.next();
        }
        String tableName = c.nextQualifiedName();
        TableBuilder table = tableName == null ? null : tables.get(tableName);
        if (table == null) {
            skipped.add(summary("CREATE INDEX (대상 테이블 없음)", tokens));
            return;
        }
        String method = readUsing(table, indexName, c);
        if (method != null && "BTREE".equals(type)) {
            type = method;
        }
        IndexKeys keys = indexKeys(table, indexName, c.nextKeyParts());
        if (keys.columns().isEmpty() && keys.expression() == null) {
            skipped.add(summary("CREATE INDEX (컬럼 없음)", tokens));
            return;
        }
        String target = table.name + "." + (indexName == null ? "?" : indexName);
        String parser = null;
        String where = null;
        List<String> include = new ArrayList<>();
        while (c.hasNext()) {
            String w = c.peekWord(0);
            if ("with".equals(w) && "parser".equals(c.peekWord(1))) {
                c.next();
                c.next();
                parser = c.nextIdentifier();
            } else if ("include".equals(w) && "(".equals(c.peekRaw(1))) {
                c.next();
                include.addAll(c.nextIdentifierList());
            } else if ("where".equals(w)) {
                c.next();
                where = stripOuterParens(joinExpression(c.rest()));
            } else if ("using".equals(w)) {
                String after = readUsing(table, indexName, c); // MySQL — 키 뒤의 USING HASH
                if (after != null && "BTREE".equals(type)) {
                    type = after;
                }
            } else if ("with".equals(w) && "(".equals(c.peekRaw(1))) {
                c.next();
                table.warnings.add(target + ": WITH (" + c.nextParenExpression() + ") — 문서에 담지 않음");
            } else if ("nulls".equals(w)) {
                c.next();
                StringBuilder clause = new StringBuilder("NULLS");
                while (c.hasNext() && ("not".equals(c.peekWord(0)) || "distinct".equals(c.peekWord(0)))) {
                    clause.append(' ').append(c.nextWord().toUpperCase(Locale.ROOT));
                }
                table.warnings.add(target + ": " + clause + " — 문서에 담지 않음");
            } else if ("tablespace".equals(w)) {
                c.next();
                c.next();
            } else {
                c.next(); // MySQL ALGORITHM·LOCK·COMMENT …
            }
        }
        if (unique && keys.plainColumns() && where == null && include.isEmpty() && "BTREE".equals(type)) {
            List<String> columns = keys.names();
            table.uniques.add(uniqueOf(indexName != null ? indexName
                    : "uk_" + table.name + "_" + String.join("_", columns), columns));
            return;
        }
        putIndex(table, indexName, type, parser, keys, unique, where, include);
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
        if ("only".equals(c.peekWord(0)) && c.peek(1) != null && !"add".equals(c.peekWord(1))) {
            c.next(); // pg_dump의 ALTER TABLE ONLY t — 상속 테이블에 퍼뜨리지 않는다는 표시일 뿐이다
        }
        String tableName = c.nextQualifiedName();
        if (tableName != null && "alter".equals(c.peekWord(0)) && parseAlterColumnIdentity(c, tables.get(tableName))) {
            return;
        }
        if (tableName == null || !"add".equals(c.peekWord(0))) {
            skipped.add(summary("ALTER TABLE (지원하지 않는 형태)", tokens));
            return;
        }
        c.next(); // ADD — 컬럼 추가 등 정의 변경은 읽지 않는다(이미 CREATE에 있다고 본다)
        List<Token> addItem = tokens.subList(c.index(), tokens.size());
        Cursor item = new Cursor(addItem);
        // 인덱스·CHECK는 CREATE TABLE 안의 제약과 같은 문법이다
        String addHead = "constraint".equals(item.peekWord(0)) ? item.peekWord(2) : item.peekWord(0);
        if (addHead != null && Set.of("check", "key", "index", "fulltext", "spatial").contains(addHead)) {
            TableBuilder target = tables.get(tableName);
            if (target == null) {
                skipped.add(summary("ALTER TABLE ADD (대상 테이블 없음)", tokens));
                return;
            }
            parseTableConstraint(target, item, addItem, skipped);
            return;
        }

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
                    table.primaryKeyName = constraintName != null ? constraintName : defaultPrimaryKeyName(table.name);
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

    /** ALTER COLUMN c ADD GENERATED {ALWAYS|BY DEFAULT} AS IDENTITY [(…)] — pg_dump가 IDENTITY를 이렇게 낸다.
     *  이 꼴이면 읽고 true, 아니면 커서를 건드리지 않은 채 false */
    private static boolean parseAlterColumnIdentity(Cursor c, TableBuilder table) {
        int column = "column".equals(c.peekWord(1)) ? 2 : 1;
        if (!"add".equals(c.peekWord(column + 1)) || !"generated".equals(c.peekWord(column + 2))) {
            return false;
        }
        c.next(); // ALTER
        if (column == 2) {
            c.next(); // COLUMN
        }
        String columnName = c.nextIdentifier();
        c.next(); // ADD
        c.next(); // GENERATED
        boolean always = "always".equals(c.peekWord(0));
        ColumnBuilder target = table == null || columnName == null ? null : table.columns.get(columnName);
        if (target != null) {
            target.autoIncrement = true;
            target.identityAlways = always;
            target.defaultValue = null;
        }
        return true;
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

    /** 식 바깥 괄호 한 겹 — {@code (a > 0)}은 {@code a > 0}. {@code (a) OR (b)}는 그대로 */
    static String stripOuterParens(String text) {
        String trimmed = text == null ? null : text.trim();
        if (trimmed == null || !trimmed.startsWith("(") || !trimmed.endsWith(")")) {
            return trimmed;
        }
        int depth = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (depth == 0 && i < trimmed.length() - 1) {
                return trimmed;
            }
        }
        return trimmed.substring(1, trimmed.length() - 1).trim();
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

    /** 연산자 기호 — 이웃한 기호 사이에는 공백을 넣지 않는다(>=·<>·::·||) */
    private static final String OPERATOR_CHARS = "<>=!|&:";

    /** 토큰을 식 원문으로 — 토큰 사이에 공백 하나를 두되, 괄호 안쪽·쉼표 앞·점 양옆·
     *  연산자 기호끼리는 붙인다. 문자열·quoted 식별자는 원문(따옴표째)을 쓴다 */
    static String joinExpression(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        Token previous = null;
        for (Token token : tokens) {
            String raw = token.raw();
            if (previous != null) {
                String prev = previous.raw();
                boolean glue = "(".equals(prev) || ")".equals(raw) || ",".equals(raw)
                        || ".".equals(raw) || ".".equals(prev)
                        || (prev.length() == 1 && raw.length() == 1
                            && OPERATOR_CHARS.indexOf(prev.charAt(0)) >= 0 && OPERATOR_CHARS.indexOf(raw.charAt(0)) >= 0)
                        || ("(".equals(raw) && previous.isWord() && !isSqlKeyword(previous.text()));
                if (!glue) {
                    sb.append(' ');
                }
            }
            sb.append(raw);
            previous = token;
        }
        return sb.toString();
    }

    /** 함수 이름이 아니라 키워드 뒤의 괄호 — IN (…)·AND (…) 사이는 띄운다 */
    private static boolean isSqlKeyword(String word) {
        return Set.of("in", "and", "or", "not", "is", "as", "when", "then", "else", "case", "exists", "between", "like")
                .contains(word.toLowerCase(Locale.ROOT));
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
                if (ch == '-') {
                    i++; // 부호를 먼저 넘긴다 — 넘기지 않으면 제자리에서 빈 토큰을 끝없이 만든다
                }
                while (i < statement.length() && (Character.isDigit(statement.charAt(i)) || statement.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new PunctToken(statement.substring(start, i)));
                continue;
            }
            if (OPERATOR_RUN.indexOf(ch) >= 0) {
                int end = operatorEnd(statement, i);
                tokens.add(new PunctToken(statement.substring(i, end)));
                i = end;
                continue;
            }
            tokens.add(new PunctToken(String.valueOf(ch)));
            i++;
        }
        return tokens;
    }

    /** 캐스트 타입 이름 뒤에 올 수 있는 컬럼 옵션 — 여기서 캐스트를 끝낸다 */
    private static final Set<String> CAST_STOP_WORDS = Set.of("not", "null", "default", "constraint", "check",
            "references", "primary", "unique", "collate", "generated", "on", "comment", "auto_increment");

    /** 이어 쓰면 연산자 하나가 되는 기호 — PostgreSQL 연산자 문자(!~·~*·@>·->>·#>·@@ — 신고 44) */
    private static final String OPERATOR_RUN = "+-*/<>=~!@#%^&|`?";

    /** 붙은 기호 묶음 하나의 끝 — PostgreSQL 어휘 규칙을 따른다. 여러 글자 연산자는 ~ ! @ # % ^ & | ` ? 중
     *  하나를 품지 않으면 + 나 - 로 끝날 수 없다({@code >-1}은 {@code >}와 {@code -1}) */
    private static int operatorEnd(String statement, int start) {
        int end = start;
        while (end < statement.length() && OPERATOR_RUN.indexOf(statement.charAt(end)) >= 0) {
            end++;
        }
        while (end - start > 1 && "+-".indexOf(statement.charAt(end - 1)) >= 0
                && statement.substring(start, end).chars().noneMatch(c -> "~!@#%^&|`?".indexOf(c) >= 0)) {
            end--;
        }
        return end;
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

        /** ( 식 ) — 바깥 괄호 없는 식 원문. 괄호가 아니면 null */
        String nextParenExpression() {
            if (!"(".equals(peekRaw(0))) {
                return null;
            }
            int open = index;
            int close = matchingParen(tokens, open);
            String expression = joinExpression(tokens.subList(open + 1, close));
            index = Math.min(close + 1, tokens.size());
            return expression;
        }

        /** DEFAULT·ON UPDATE 값 — 문자열은 따옴표를 벗긴 값(content 관례), 괄호 식은 괄호째,
         *  함수 호출은 인자째(CURRENT_TIMESTAMP(6)), 단어는 원문 대소문자. NULL은 null */
        String nextValueExpression() {
            if (!hasNext()) {
                return null;
            }
            if ("(".equals(peekRaw(0))) {
                return "(" + nextParenExpression() + ")";
            }
            Token token = next();
            if (token.isString()) {
                skipCast();
                return token.text();
            }
            if (token.isWord() && "null".equals(token.text())) {
                skipCast();
                return null;
            }
            if (token.isWord() && "(".equals(peekRaw(0))) {
                return token.raw() + "(" + nextParenExpression() + ")";
            }
            String value = token.isWord() ? token.raw() : token.text();
            skipCast();
            return value;
        }

        /** 값 뒤의 PostgreSQL 캐스트({@code ''::character varying}) — 타입 단어를 컬럼 옵션으로 잘못 읽지 않게 넘긴다 */
        private void skipCast() {
            while (":".equals(peekRaw(0)) && ":".equals(peekRaw(1))) {
                next();
                next();
                while (hasNext() && peek(0).isWord() && !CAST_STOP_WORDS.contains(peek(0).text())) {
                    next();
                    if ("(".equals(peekRaw(0))) {
                        nextParenExpression();
                    }
                }
                while ("[]".equals(peekRaw(0))) { // 배열 표기 — 토큰화가 quoted 식별자 한 개로 읽는다
                    next();
                }
            }
        }

        /** ( 키 조각, … ) — 조각은 {@code 컬럼 [(길이)] [연산자 클래스] [COLLATE c] [ASC|DESC] [NULLS FIRST|LAST]}
         *  또는 식 — {@code (식)}·{@code lower(name)} 같은 함수 호출(이름 null, 원문은 raw) */
        List<KeyPart> nextKeyParts() {
            if (!"(".equals(peekRaw(0))) {
                return List.of();
            }
            int close = matchingParen(tokens, index);
            next();
            List<KeyPart> parts = new ArrayList<>();
            while (index < close) {
                int end = itemEnd(tokens, index, close);
                List<Token> part = tokens.subList(index, end);
                index = end + 1;
                if (part.isEmpty()) {
                    continue;
                }
                String raw = joinExpression(part);
                Token first = part.get(0);
                boolean prefixLength = part.size() > 3 && "(".equals(part.get(1).raw())
                        && isNumber(part.get(2).text()) && ")".equals(part.get(3).raw());
                boolean call = part.size() > 1 && "(".equals(part.get(1).raw()) && !prefixLength;
                if (!first.isIdentifier() || first.isString() || "(".equals(first.raw()) || call) {
                    parts.add(new KeyPart(null, "ASC", null, null, raw, null));
                    continue;
                }
                String prefix = prefixLength ? part.get(2).text() : null;
                String order = "ASC";
                String opclass = null;
                List<String> ignored = new ArrayList<>();
                for (int i = prefixLength ? 4 : 1; i < part.size(); i++) {
                    Token t = part.get(i);
                    String w = t.isWord() ? t.text() : null;
                    if ("desc".equals(w) || "asc".equals(w)) {
                        order = w.toUpperCase(Locale.ROOT);
                    } else if ("nulls".equals(w) && i + 1 < part.size()) {
                        ignored.add("NULLS " + part.get(++i).raw().toUpperCase(Locale.ROOT));
                    } else if ("collate".equals(w) && i + 1 < part.size()) {
                        ignored.add("COLLATE " + part.get(++i).raw());
                    } else if (t.isIdentifier() && !t.isString() && opclass == null) {
                        // 연산자 클래스 — 스키마 한정(public.gin_trgm_ops)도 받는다
                        StringBuilder name = new StringBuilder(t.raw());
                        while (i + 2 < part.size() && ".".equals(part.get(i + 1).raw())) {
                            name.append('.').append(part.get(i + 2).raw());
                            i += 2;
                        }
                        opclass = name.toString();
                    }
                }
                parts.add(new KeyPart(first.text(), order, prefix, opclass, raw,
                        ignored.isEmpty() ? null : String.join(", ", ignored)));
            }
            index = Math.min(close + 1, tokens.size());
            return parts;
        }

        /** 남은 토큰 전부 — 커서를 끝으로 옮긴다 */
        List<Token> rest() {
            List<Token> out = List.copyOf(tokens.subList(Math.min(index, tokens.size()), tokens.size()));
            index = tokens.size();
            return out;
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
