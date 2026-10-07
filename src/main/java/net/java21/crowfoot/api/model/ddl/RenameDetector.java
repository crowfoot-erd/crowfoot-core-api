package net.java21.crowfoot.api.model.ddl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 문서↔실제 DB 마이그레이션의 이름 변경 감지 (05-editor/04-dbms-engineering.md §3.3 — v1.35).
 *
 * <p>{@link SchemaDiffer}는 이름이 정체성이라 물리명을 바꾸면 삭제 후 추가가 되고 그 데이터가 사라진다.
 * 문서의 테이블·컬럼 id는 버전이 바뀌어도 그대로다. 그래서 문서 버전 기록에서 같은 id의 예전 이름을 찾고,
 * DB에 그 예전 이름이 남아 있으면 이름 변경으로 본다. 감지한 변경은 DB 쪽 본체의 이름을 지금 이름으로
 * 바꿔 차이 계산에 넘기고(남은 타입·NULL 변경은 평소대로 나온다), {@code RENAME} 문장으로 앞에 낸다.
 *
 * <p>감지 조건(모두 만족해야 한다):
 * <ul>
 *   <li>지금 이름이 DB에 없다</li>
 *   <li>같은 id가 예전 버전에서 쓴 이름이 DB에 있다</li>
 *   <li>그 예전 이름을 지금 문서의 다른 객체가 쓰지 않는다(두 객체가 이름을 맞바꾼 경우 등은 감지하지 않는다)</li>
 * </ul>
 */
public final class RenameDetector {

    public static final String KIND_TABLE = "TABLE";
    public static final String KIND_COLUMN = "COLUMN";

    /** 이름 변경 — 컬럼이면 table은 (바뀐 뒤의) 테이블 물리명, 테이블이면 table은 null */
    public record Rename(String kind, String table, String from, String to) {
    }

    /** adjustedFrom은 DB 본체의 예전 이름을 지금 이름으로 바꾼 것 — 차이 계산은 이것과 문서를 비교한다 */
    public record Result(DdlContent adjustedFrom, List<Rename> renames) {
    }

    private RenameDetector() {
    }

    public static Result detect(DdlContent db, DdlContent document, List<DdlContent> previousDocuments) {
        List<Rename> renames = new ArrayList<>();
        Map<String, Set<String>> previousTableNames = new HashMap<>();
        Map<String, Set<String>> previousColumnNames = new HashMap<>(); // "tableId/columnId" → 예전 이름들
        for (DdlContent previous : previousDocuments) {
            for (DdlContent.Table table : previous.tables()) {
                if (table.id() == null) {
                    continue;
                }
                previousTableNames.computeIfAbsent(table.id(), k -> new LinkedHashSet<>()).add(key(table.physicalName()));
                for (DdlContent.Column column : table.columns()) {
                    if (column.id() != null) {
                        previousColumnNames.computeIfAbsent(table.id() + "/" + column.id(), k -> new LinkedHashSet<>())
                                .add(key(column.physicalName()));
                    }
                }
            }
        }

        Set<String> documentTableNames = new HashSet<>();
        document.tables().forEach(table -> documentTableNames.add(key(table.physicalName())));
        Map<String, DdlContent.Table> dbTables = new HashMap<>();
        db.tables().forEach(table -> dbTables.putIfAbsent(key(table.physicalName()), table));

        // 1) 테이블 — 지금 이름이 DB에 없고 예전 이름이 DB에 있으면 이름 변경
        Map<String, String> tableRenameByOldKey = new HashMap<>(); // DB 예전 이름 → 지금 이름(원문)
        for (DdlContent.Table table : document.tables()) {
            String current = key(table.physicalName());
            if (dbTables.containsKey(current) || table.id() == null) {
                continue;
            }
            for (String old : previousTableNames.getOrDefault(table.id(), Set.of())) {
                if (!old.equals(current) && dbTables.containsKey(old) && !documentTableNames.contains(old)
                        && !tableRenameByOldKey.containsKey(old)) {
                    tableRenameByOldKey.put(old, table.physicalName());
                    renames.add(new Rename(KIND_TABLE, null, dbTables.get(old).physicalName(), table.physicalName()));
                    break;
                }
            }
        }

        // 2) 컬럼 — 같은 테이블(이름 변경 반영 후) 안에서 같은 규칙
        Map<String, Map<String, String>> columnRenames = new HashMap<>(); // DB 테이블 예전 키 → (예전 컬럼 키 → 지금 이름)
        for (DdlContent.Table table : document.tables()) {
            if (table.id() == null) {
                continue;
            }
            String dbKey = key(table.physicalName());
            for (Map.Entry<String, String> entry : tableRenameByOldKey.entrySet()) {
                if (key(entry.getValue()).equals(dbKey)) {
                    dbKey = entry.getKey();
                }
            }
            DdlContent.Table dbTable = dbTables.get(dbKey);
            if (dbTable == null) {
                continue;
            }
            Set<String> dbColumns = new HashSet<>();
            dbTable.columns().forEach(column -> dbColumns.add(key(column.physicalName())));
            Set<String> documentColumns = new HashSet<>();
            table.columns().forEach(column -> documentColumns.add(key(column.physicalName())));
            Map<String, String> renamedHere = new HashMap<>();
            for (DdlContent.Column column : table.columns()) {
                String current = key(column.physicalName());
                if (dbColumns.contains(current) || column.id() == null) {
                    continue;
                }
                for (String old : previousColumnNames.getOrDefault(table.id() + "/" + column.id(), Set.of())) {
                    if (!old.equals(current) && dbColumns.contains(old) && !documentColumns.contains(old)
                            && !renamedHere.containsKey(old)) {
                        renamedHere.put(old, column.physicalName());
                        renames.add(new Rename(KIND_COLUMN, table.physicalName(), columnName(dbTable, old),
                                column.physicalName()));
                        break;
                    }
                }
            }
            if (!renamedHere.isEmpty()) {
                columnRenames.put(dbKey, renamedHere);
            }
        }

        if (renames.isEmpty()) {
            return new Result(db, List.of());
        }
        List<DdlContent.Table> adjusted = new ArrayList<>();
        for (DdlContent.Table table : db.tables()) {
            String tableKey = key(table.physicalName());
            String newTableName = tableRenameByOldKey.getOrDefault(tableKey, table.physicalName());
            Map<String, String> renamedColumns = columnRenames.getOrDefault(tableKey, Map.of());
            List<DdlContent.Column> columns = new ArrayList<>();
            for (DdlContent.Column column : table.columns()) {
                String newName = renamedColumns.get(key(column.physicalName()));
                columns.add(newName == null ? column : new DdlContent.Column(column.id(), newName, column.dataType(),
                        column.length(), column.precision(), column.scale(), column.nullable(), column.defaultValue(),
                        column.autoIncrement(), column.logicalName(), column.generated(), column.onUpdate(), column.identityAlways()));
            }
            adjusted.add(new DdlContent.Table(table.id(), newTableName, table.logicalName(), columns,
                    table.primaryKey(), table.uniques(), table.indexes(), table.checks()));
        }
        return new Result(new DdlContent(adjusted, db.relationships()), List.copyOf(renames));
    }

    private static String columnName(DdlContent.Table table, String columnKey) {
        for (DdlContent.Column column : table.columns()) {
            if (key(column.physicalName()).equals(columnKey)) {
                return column.physicalName();
            }
        }
        return columnKey;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
