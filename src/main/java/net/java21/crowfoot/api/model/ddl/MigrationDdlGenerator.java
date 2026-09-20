package net.java21.crowfoot.api.model.ddl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 마이그레이션 DDL(SQL) 생성 (05-editor/04-dbms-engineering.md §3.3) — 버전 A→B·문서↔실제 DB 비교.
 *
 * <p>{@link SchemaDiffer}의 변경 연산을 방언({@link SqlDialect}) ALTER 훅으로 문장화한다.
 * 문장 순서: 생성·추가(CREATE/ADD) → 컬럼 변경(ALTER/MODIFY·코멘트) → <b>파괴적 연산 마지막 별도
 * 블록</b>(경고 배너 — FK drop → 제약 drop → 컬럼 drop → 인덱스 drop → 테이블 drop).
 * 파괴 연산을 뒤로 미루면 되돌릴 수 없는 변경이 스크립트 말미에 모여 검토 지점이 한 곳이 된다.
 *
 * <p>같은 이름의 제약·FK 재구성은 drop이 add보다 먼저 와야 한다(ADD가 기존 이름에 충돌) —
 * 이 쌍만 예외로 drop을 add 바로 앞에 붙인다. 생성 전용(§3.1)과 달리 실행은 제공하지 않는다:
 * 결과는 복사·검토용 스크립트고, 경고(DESTRUCTIVE·NOT_INTROSPECTED)로 리스크를 선표시한다.
 */
public final class MigrationDdlGenerator {

    /** 파괴적 연산 블록 배너 — 실행 전 검토 지점 */
    private static final String DESTRUCTIVE_BANNER = "-- ⚠ 파괴적 연산 — 실행 전 데이터 손실 가능성을 확인하세요";

    public record Result(String sql, List<DdlGenerator.Warning> warnings, int statementCount) {
    }

    private MigrationDdlGenerator() {
    }

    public static Result generate(DdlContent from, DdlContent to, SqlDialect dialect, String dbmsLabel,
                                  String modelName, String fromLabel, String toLabel, boolean skipIndexes) {
        SchemaDiffer.Result diff = SchemaDiffer.diff(from, to, skipIndexes);
        List<DdlGenerator.Warning> warnings = new ArrayList<>();
        if ("common".equals(dialect.id())) {
            warnings.add(new DdlGenerator.Warning(DdlGenerator.Warning.COMMON_DIALECT,
                    "이 문서의 DBMS는 SQL 방언이 등록되지 않아 공용(논리) 표기로 생성했습니다"));
        }
        warnings.addAll(DdlGenerator.validationWarnings(to));

        List<String> createsAdds = new ArrayList<>();
        List<String> alters = new ArrayList<>();
        List<String> fkDrops = new ArrayList<>();
        List<String> constraintDrops = new ArrayList<>();
        List<String> columnDrops = new ArrayList<>();
        List<String> indexDrops = new ArrayList<>();
        List<String> tableDrops = new ArrayList<>();

        Map<String, DdlContent.Table> fromTables = tablesById(from);
        Map<String, DdlContent.Table> toTables = tablesById(to);

        for (SchemaDiffer.Change change : diff.changes()) {
            switch (change) {
                case SchemaDiffer.TableAdded added -> createsAdds.add(
                        DdlGenerator.createTableStatement(added.table(), dialect));
                case SchemaDiffer.ColumnAdded added -> createsAdds.add(
                        dialect.addColumn(added.table(), added.column()));
                case SchemaDiffer.KeyAltered altered -> keyAltered(altered, dialect, createsAdds, constraintDrops);
                case SchemaDiffer.ForeignKeyAdded added -> {
                    DdlContent.Table child = toTables.get(added.relationship().childTableId());
                    DdlContent.Table parent = toTables.get(added.relationship().parentTableId());
                    if (child != null && parent != null) {
                        String statement = DdlGenerator.foreignKeyStatement(child, parent, added.relationship());
                        if (statement != null) {
                            createsAdds.add(statement);
                        }
                    }
                }
                case SchemaDiffer.IndexAdded added -> createsAdds.add(
                        dialect.createIndex(added.table(), added.index()) + ";");
                case SchemaDiffer.CommentRefresh refresh -> {
                    // commentRefresh는 세미콜론 없이 돌려준다(commentStatements 관례) — 문장으로 조립해 붙인다
                    for (String statement : dialect.commentRefresh(refresh.table(),
                            columnByName(refresh.table(), refresh.columnPhysicalName()))) {
                        alters.add(statement + ";");
                    }
                }
                case SchemaDiffer.ColumnAltered altered -> columnAltered(altered, dialect, alters, warnings);
                case SchemaDiffer.ForeignKeyDropped dropped -> {
                    DdlContent.Table child = fromTables.get(dropped.relationship().childTableId());
                    if (child != null) {
                        fkDrops.add(dialect.dropConstraint(child, dropped.relationship().fkName(),
                                SqlDialect.KIND_FOREIGN_KEY));
                    }
                }
                case SchemaDiffer.ColumnDropped dropped -> columnDrops.add(
                        dialect.dropColumn(dropped.table(), dropped.column()));
                case SchemaDiffer.IndexDropped dropped -> indexDrops.add(
                        dialect.dropIndex(dropped.table(), dropped.index()));
                case SchemaDiffer.TableDropped dropped -> tableDrops.add(
                        "DROP TABLE " + dropped.table().physicalName() + ";");
            }
        }

        List<String> destructive = new ArrayList<>();
        destructive.addAll(fkDrops);
        destructive.addAll(constraintDrops);
        destructive.addAll(columnDrops);
        destructive.addAll(indexDrops);
        destructive.addAll(tableDrops);

        if (!destructive.isEmpty()) {
            warnings.add(new DdlGenerator.Warning(DdlGenerator.Warning.DESTRUCTIVE,
                    "파괴적 연산 " + destructive.size() + "건이 스크립트 마지막 블록에 모여 있습니다 — 실행 전 반드시 검토하세요"));
        }
        if (skipIndexes && diff.skippedIndexChanges() > 0) {
            warnings.add(new DdlGenerator.Warning(DdlGenerator.Warning.NOT_INTROSPECTED,
                    "스키마 조회가 인덱스 정의를 읽지 못해 인덱스 변경 " + diff.skippedIndexChanges()
                            + "건을 생성에서 제외했습니다"));
        }

        String title = modelName == null || modelName.isBlank() ? "" : modelName + " — ";
        String header = "-- " + title + dbmsLabel + " 마이그레이션 DDL (" + fromLabel + " → " + toLabel + ")";

        List<String> blocks = new ArrayList<>();
        blocks.add(header);
        addIfNotEmpty(blocks, createsAdds);
        addIfNotEmpty(blocks, alters);
        if (!destructive.isEmpty()) {
            blocks.add(DESTRUCTIVE_BANNER + "\n" + String.join("\n", destructive));
        }

        List<String> statements = new ArrayList<>(createsAdds);
        statements.addAll(alters);
        statements.addAll(destructive);
        return new Result(String.join("\n\n", blocks), List.copyOf(warnings), statements.size());
    }

    /* ---------- 연산별 조립 ---------- */

    /** PK·UK 재구성 — drop은 파괴 블록으로, add는 추가 블록으로. 같은 이름이면 drop을 add 바로 앞에 */
    private static void keyAltered(SchemaDiffer.KeyAltered change, SqlDialect dialect,
                                   List<String> createsAdds, List<String> constraintDrops) {
        boolean sameName = change.before() != null && change.after() != null
                && change.before().name().trim().equalsIgnoreCase(change.after().name().trim());
        if (change.before() != null && !sameName) {
            constraintDrops.add(dialect.dropConstraint(change.table(), change.before().name(), change.kind()));
        }
        if (change.after() != null) {
            String definitionKind = SqlDialect.KIND_PRIMARY.equals(change.kind()) ? "PRIMARY KEY" : "UNIQUE";
            String definition = DdlGenerator.constraintDefinition(definitionKind, change.table(), change.after());
            if (definition != null) {
                if (sameName) {
                    createsAdds.add(dialect.dropConstraint(change.table(), change.before().name(), change.kind()));
                }
                createsAdds.add("ALTER TABLE " + change.table().physicalName() + " ADD " + definition + ";");
            }
        }
    }

    private static void columnAltered(SchemaDiffer.ColumnAltered change, SqlDialect dialect,
                                      List<String> alters, List<DdlGenerator.Warning> warnings) {
        List<String> fields = change.changedFields();
        String target = change.table().physicalName() + "." + change.after().physicalName();
        boolean mysql = "mysql".equals(dialect.id());
        boolean autoIncrementOnly = fields.size() == 1 && fields.contains(SchemaDiffer.FIELD_AUTO_INCREMENT);

        // MySQL MODIFY만 자동 증가 변경을 문장에 담을 수 있다 — 나머지 방언은 경고로만 알린다
        if (autoIncrementOnly && !mysql) {
            warnings.add(new DdlGenerator.Warning(DdlGenerator.Warning.VALIDATION,
                    "자동 증가 변경은 이 DBMS의 ALTER 문으로 반영하지 않습니다: " + target));
            return;
        }
        String statement = dialect.alterColumn(change.table(), change.before(), change.after());
        if (!statement.isBlank()) {
            alters.add(statement);
        }
        if (fields.contains(SchemaDiffer.FIELD_DEFAULT) && "mssql".equals(dialect.id())) {
            warnings.add(new DdlGenerator.Warning(DdlGenerator.Warning.VALIDATION,
                    "SQL Server는 ALTER COLUMN으로 기본값을 바꿀 수 없습니다 — DEFAULT 제약을 별도로 관리하세요: "
                            + target));
        }
        if (fields.contains(SchemaDiffer.FIELD_AUTO_INCREMENT) && !mysql) {
            warnings.add(new DdlGenerator.Warning(DdlGenerator.Warning.VALIDATION,
                    "자동 증가 변경은 이 DBMS의 ALTER 문으로 반영하지 않습니다: " + target));
        }
    }

    /* ---------- 공용 헬퍼 ---------- */

    private static Map<String, DdlContent.Table> tablesById(DdlContent content) {
        Map<String, DdlContent.Table> byId = new HashMap<>();
        for (DdlContent.Table table : content.tables()) {
            if (table.id() != null) {
                byId.putIfAbsent(table.id(), table);
            }
        }
        return byId;
    }

    private static DdlContent.Column columnByName(DdlContent.Table table, String columnPhysicalName) {
        if (columnPhysicalName == null) {
            return null; // 테이블 코멘트 갱신
        }
        for (DdlContent.Column column : table.columns()) {
            if (column.physicalName().equalsIgnoreCase(columnPhysicalName)) {
                return column;
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
