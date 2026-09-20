package net.java21.crowfoot.api.model.ddl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 두 문서(content)의 구조 차이 계산 — 마이그레이션 DDL(05-editor/04-dbms-engineering.md §3.3)의 원천.
 *
 * <p>순수 함수다: 권한·방언·SQL 표기를 모르고 변경 연산 목록만 만든다.
 * 매칭은 물리명 trim+대소문자 무시로 한다 — 컬럼 id는 문서마다 다르게 발급되므로
 * (리버스 엔지니어링이 UUID로 새로 만든다) 버전 비교·문서↔DB 비교 모두에서 안전하다.
 * 개명은 remove+add로 판정한다(이름이 정체성이다).
 *
 * <p>판정 규칙:
 * <ul>
 *   <li>기본값 {@code ''} ≡ null — introspection 계열이 빈 기본값을 null로 돌려주는 차이를 흡수한다</li>
 *   <li>PK는 <b>컬럼 목록</b>이 정체성이다 — 이름(MySQL의 상수명 PRIMARY → PK_{table} 정규화 등)은
 *       DBMS 산물이라 이름만 다른 PK를 재구성 대상으로 보면 오탐이 폭발한다</li>
 *   <li>UK는 이름이 정체성이다(카탈로그에 실제로 존재하는 이름) — 이름+컬럼 순서가 다르면 재구성(drop+add)</li>
 *   <li>FK는 (child, fkName) 1차 매칭 후 (parent, child) 폴백 — 리버스가 카탈로그명을 그대로 쓰지만
 *       문서 쪽 이름이 달라질 수 있어 테이블 쌍으로도 잡는다</li>
 *   <li>{@code skipIndexes} — 문서↔실제 DB 비교에서 쓴다. introspection이 인덱스를 읽지 못해
 *       (조립 결과가 항상 빈 배열) 문서의 인덱스가 전부 신규로 보이는 오탐을 막는다.
 *       제외한 건수는 {@link Result#skippedIndexChanges()}로 돌려 경고(NOT_INTROSPECTED)를 준다</li>
 * </ul>
 */
public final class SchemaDiffer {

    /** ColumnAltered.changedFields 원소 — 구조 변경 종류 */
    public static final String FIELD_TYPE = "TYPE";
    public static final String FIELD_NULLABLE = "NULLABLE";
    public static final String FIELD_DEFAULT = "DEFAULT";
    public static final String FIELD_AUTO_INCREMENT = "AUTO_INCREMENT";

    private SchemaDiffer() {
    }

    /** changes — 추가→변경→삭제 순으로 쌓는다(생성기가 블록을 다시 조립하므로 순서는 참고용) */
    public record Result(List<Change> changes, int skippedIndexChanges) {
    }

    public sealed interface Change permits TableAdded, TableDropped, ColumnAdded, ColumnDropped, ColumnAltered,
            KeyAltered, ForeignKeyAdded, ForeignKeyDropped, IndexAdded, IndexDropped, CommentRefresh {
    }

    public record TableAdded(DdlContent.Table table) implements Change {
    }

    public record TableDropped(DdlContent.Table table) implements Change {
    }

    public record ColumnAdded(DdlContent.Table table, DdlContent.Column column) implements Change {
    }

    public record ColumnDropped(DdlContent.Table table, DdlContent.Column column) implements Change {
    }

    /** changedFields — FIELD_* 상수 목록. 논리명 변경은 여기 오지 않고 CommentRefresh로 갈라진다 */
    public record ColumnAltered(DdlContent.Table table, DdlContent.Column before, DdlContent.Column after,
                                List<String> changedFields) implements Change {
    }

    /** kind — SqlDialect.KIND_PRIMARY·KIND_UNIQUE. before/after는 null 가능(단독 삭제·추가),
     *  둘 다 있으면 재구성(생성기가 drop→add로 내보낸다). table은 항상 to 쪽 */
    public record KeyAltered(DdlContent.Table table, String kind,
                             DdlContent.KeyConstraint before, DdlContent.KeyConstraint after) implements Change {
    }

    /** relationship의 테이블 id는 to 문서 기준 — 생성기가 to 문서에서 child·parent를 찾는다 */
    public record ForeignKeyAdded(DdlContent.Relationship relationship) implements Change {
    }

    /** relationship의 테이블 id는 from 문서 기준 */
    public record ForeignKeyDropped(DdlContent.Relationship relationship) implements Change {
    }

    public record IndexAdded(DdlContent.Table table, DdlContent.Index index) implements Change {
    }

    public record IndexDropped(DdlContent.Table table, DdlContent.Index index) implements Change {
    }

    /** 코멘트(논리명) 갱신 — columnPhysicalName이 null이면 테이블 코멘트. table은 to 쪽 */
    public record CommentRefresh(DdlContent.Table table, String columnPhysicalName) implements Change {
    }

    public static Result diff(DdlContent from, DdlContent to, boolean skipIndexes) {
        List<Change> changes = new ArrayList<>();
        int skippedIndexes = 0;

        // 물리명 키(테이블 매칭·생존 검사)와 id 키(FK의 참조 해석)는 다른 지도다 — id는 문서마다 다르게 발급된다
        Map<String, DdlContent.Table> fromTablesByName = tablesByKey(from.tables());
        Map<String, DdlContent.Table> toTablesByName = tablesByKey(to.tables());

        for (DdlContent.Table toTable : to.tables()) {
            DdlContent.Table fromTable = fromTablesByName.get(key(toTable.physicalName()));
            if (fromTable == null) {
                changes.add(new TableAdded(toTable));
                continue;
            }
            skippedIndexes += diffTable(fromTable, toTable, skipIndexes, changes);
        }
        for (DdlContent.Table fromTable : from.tables()) {
            if (!toTablesByName.containsKey(key(fromTable.physicalName()))) {
                changes.add(new TableDropped(fromTable));
            }
        }
        diffForeignKeys(from, fromTablesByName, tablesById(from.tables()), to, toTablesByName,
                tablesById(to.tables()), changes);
        return new Result(List.copyOf(changes), skippedIndexes);
    }

    /* ---------- 테이블 단위 — 컬럼·PK·UK·인덱스·테이블 코멘트 ---------- */

    private static int diffTable(DdlContent.Table fromTable, DdlContent.Table toTable, boolean skipIndexes,
                                 List<Change> changes) {
        int skipped = 0;
        Map<String, DdlContent.Column> fromColumns = columnsByKey(fromTable);
        Map<String, DdlContent.Column> toColumns = columnsByKey(toTable);

        for (DdlContent.Column toColumn : toTable.columns()) {
            DdlContent.Column fromColumn = fromColumns.get(key(toColumn.physicalName()));
            if (fromColumn == null) {
                changes.add(new ColumnAdded(toTable, toColumn));
                continue;
            }
            List<String> fields = changedFields(fromColumn, toColumn);
            if (!fields.isEmpty()) {
                changes.add(new ColumnAltered(toTable, fromColumn, toColumn, fields));
            } else if (!Objects.equals(fromColumn.logicalName(), toColumn.logicalName())) {
                changes.add(new CommentRefresh(toTable, toColumn.physicalName()));
            }
        }
        for (DdlContent.Column fromColumn : fromTable.columns()) {
            if (!toColumns.containsKey(key(fromColumn.physicalName()))) {
                changes.add(new ColumnDropped(toTable, fromColumn));
            }
        }

        // PK — 컬럼 목록이 다를 때만 재구성(이름은 DBMS 산물이라 무시한다)
        if (!sameColumnRefs(fromTable, fromTable.primaryKey(), toTable, toTable.primaryKey())) {
            changes.add(new KeyAltered(toTable, SqlDialect.KIND_PRIMARY, fromTable.primaryKey(), toTable.primaryKey()));
        }

        // UK — 이름이 정체성. 짝이 없으면 단독 추가·삭제, 컬럼이 다르면 재구성
        for (DdlContent.KeyConstraint toUnique : toTable.uniques()) {
            DdlContent.KeyConstraint fromUnique = findKeyByName(fromTable.uniques(), toUnique.name());
            if (fromUnique == null) {
                changes.add(new KeyAltered(toTable, SqlDialect.KIND_UNIQUE, null, toUnique));
            } else if (!sameColumnRefs(fromTable, fromUnique, toTable, toUnique)) {
                changes.add(new KeyAltered(toTable, SqlDialect.KIND_UNIQUE, fromUnique, toUnique));
            }
        }
        for (DdlContent.KeyConstraint fromUnique : fromTable.uniques()) {
            if (findKeyByName(toTable.uniques(), fromUnique.name()) == null) {
                changes.add(new KeyAltered(toTable, SqlDialect.KIND_UNIQUE, fromUnique, null));
            }
        }

        List<Change> indexChanges = indexChanges(fromTable, toTable);
        if (skipIndexes) {
            skipped += indexChanges.size();
        } else {
            changes.addAll(indexChanges);
        }

        if (!Objects.equals(fromTable.logicalName(), toTable.logicalName())) {
            changes.add(new CommentRefresh(toTable, null));
        }
        return skipped;
    }

    private static List<String> changedFields(DdlContent.Column before, DdlContent.Column after) {
        List<String> fields = new ArrayList<>();
        if (!DdlGenerator.sameType(before, after)) {
            fields.add(FIELD_TYPE);
        }
        if (before.nullable() != after.nullable()) {
            fields.add(FIELD_NULLABLE);
        }
        if (!Objects.equals(DdlGenerator.normalizedDefault(before), DdlGenerator.normalizedDefault(after))) {
            fields.add(FIELD_DEFAULT);
        }
        if (before.autoIncrement() != after.autoIncrement()) {
            fields.add(FIELD_AUTO_INCREMENT);
        }
        return List.copyOf(fields);
    }

    private static List<Change> indexChanges(DdlContent.Table fromTable, DdlContent.Table toTable) {
        List<Change> changes = new ArrayList<>();
        for (DdlContent.Index toIndex : toTable.indexes()) {
            DdlContent.Index fromIndex = findIndexByName(fromTable.indexes(), toIndex.name());
            if (fromIndex == null) {
                changes.add(new IndexAdded(toTable, toIndex));
            } else if (!sameIndexColumns(fromTable, fromIndex, toTable, toIndex)) {
                changes.add(new IndexDropped(toTable, fromIndex));
                changes.add(new IndexAdded(toTable, toIndex));
            }
        }
        for (DdlContent.Index fromIndex : fromTable.indexes()) {
            if (findIndexByName(toTable.indexes(), fromIndex.name()) == null) {
                changes.add(new IndexDropped(toTable, fromIndex));
            }
        }
        return changes;
    }

    /* ---------- FK — 문서 전체 단위 매칭 ---------- */

    private static void diffForeignKeys(DdlContent from, Map<String, DdlContent.Table> fromTablesByName,
                                        Map<String, DdlContent.Table> fromTablesById, DdlContent to,
                                        Map<String, DdlContent.Table> toTablesByName,
                                        Map<String, DdlContent.Table> toTablesById, List<Change> changes) {
        List<DdlContent.Relationship> remaining = new ArrayList<>(from.relationships());
        for (DdlContent.Relationship toRel : to.relationships()) {
            DdlContent.Relationship matched = takeMatched(remaining, toRel, fromTablesById, toTablesById);
            if (matched == null) {
                changes.add(new ForeignKeyAdded(toRel));
            } else if (!sameForeignKey(matched, fromTablesById, toRel, toTablesById)) {
                changes.add(new ForeignKeyDropped(matched));
                changes.add(new ForeignKeyAdded(toRel));
            }
        }
        for (DdlContent.Relationship rel : remaining) {
            DdlContent.Table child = fromTablesById.get(rel.childTableId());
            // 사라지는 테이블의 FK는 DROP TABLE이 정리한다 — 삭제 문장을 재차 내지 않는다
            if (child == null || toTablesByName.containsKey(key(child.physicalName()))) {
                changes.add(new ForeignKeyDropped(rel));
            }
        }
    }

    /** (child, fkName) 1차 → (parent, child) 폴백 매칭. 잡히면 후보 목록에서 제거해 1:1을 유지한다 */
    private static DdlContent.Relationship takeMatched(List<DdlContent.Relationship> remaining,
                                                       DdlContent.Relationship toRel,
                                                       Map<String, DdlContent.Table> fromTablesById,
                                                       Map<String, DdlContent.Table> toTablesById) {
        String toChild = tableKey(toTablesById, toRel.childTableId());
        for (Iterator<DdlContent.Relationship> it = remaining.iterator(); it.hasNext();) {
            DdlContent.Relationship candidate = it.next();
            if (Objects.equals(tableKey(fromTablesById, candidate.childTableId()), toChild)
                    && Objects.equals(key(candidate.fkName()), key(toRel.fkName()))) {
                it.remove();
                return candidate;
            }
        }
        String toParent = tableKey(toTablesById, toRel.parentTableId());
        for (Iterator<DdlContent.Relationship> it = remaining.iterator(); it.hasNext();) {
            DdlContent.Relationship candidate = it.next();
            if (Objects.equals(tableKey(fromTablesById, candidate.parentTableId()), toParent)
                    && Objects.equals(tableKey(fromTablesById, candidate.childTableId()), toChild)) {
                it.remove();
                return candidate;
            }
        }
        return null;
    }

    /** FK 정의 동등 — 부모 테이블·매핑 컬럼 쌍(순서 포함)·참조 동작. id는 문서마다 다르니 물리명으로 본다 */
    private static boolean sameForeignKey(DdlContent.Relationship before, Map<String, DdlContent.Table> fromTablesById,
                                          DdlContent.Relationship after, Map<String, DdlContent.Table> toTablesById) {
        if (!Objects.equals(tableKey(fromTablesById, before.parentTableId()),
                tableKey(toTablesById, after.parentTableId()))) {
            return false;
        }
        DdlContent.Table fromChild = fromTablesById.get(before.childTableId());
        DdlContent.Table fromParent = fromTablesById.get(before.parentTableId());
        DdlContent.Table toChild = toTablesById.get(after.childTableId());
        DdlContent.Table toParent = toTablesById.get(after.parentTableId());
        if (fromChild == null || fromParent == null || toChild == null || toParent == null) {
            return false;
        }
        List<String> fromPairs = mappingPairs(fromChild, fromParent, before);
        List<String> toPairs = mappingPairs(toChild, toParent, after);
        return Objects.equals(fromPairs, toPairs)
                && Objects.equals(action(before.onDelete()), action(after.onDelete()))
                && Objects.equals(action(before.onUpdate()), action(after.onUpdate()));
    }

    /** 매핑 순서쌍 "부모컬럼→자식컬럼" 목록 — 순서가 곧 정의다 */
    private static List<String> mappingPairs(DdlContent.Table child, DdlContent.Table parent,
                                             DdlContent.Relationship rel) {
        List<String> pairs = new ArrayList<>();
        for (DdlContent.ColumnMapping mapping : rel.columnMappings()) {
            String parentName = physicalName(parent, mapping.parentColumnId());
            String childName = physicalName(child, mapping.childColumnId());
            if (parentName != null && childName != null) {
                pairs.add(parentName + "→" + childName);
            }
        }
        return pairs;
    }

    /** 참조 동작 정규화 — null·빈값은 NO_ACTION(리버스 조립 기본값)과 같은 것으로 본다 */
    private static String action(String value) {
        return value == null || value.isBlank() ? "NO_ACTION" : value;
    }

    /* ---------- 공용 헬퍼 ---------- */

    private static String key(String name) {
        return name.trim().toLowerCase();
    }

    private static Map<String, DdlContent.Table> tablesByKey(List<DdlContent.Table> tables) {
        Map<String, DdlContent.Table> byKey = new HashMap<>();
        for (DdlContent.Table table : tables) {
            byKey.putIfAbsent(key(table.physicalName()), table);
        }
        return byKey;
    }

    private static Map<String, DdlContent.Table> tablesById(List<DdlContent.Table> tables) {
        Map<String, DdlContent.Table> byId = new HashMap<>();
        for (DdlContent.Table table : tables) {
            if (table.id() != null) {
                byId.putIfAbsent(table.id(), table);
            }
        }
        return byId;
    }

    private static Map<String, DdlContent.Column> columnsByKey(DdlContent.Table table) {
        Map<String, DdlContent.Column> byKey = new HashMap<>();
        for (DdlContent.Column column : table.columns()) {
            byKey.putIfAbsent(key(column.physicalName()), column);
        }
        return byKey;
    }

    private static String tableKey(Map<String, DdlContent.Table> tables, String tableId) {
        DdlContent.Table table = tables.get(tableId);
        return table == null ? null : key(table.physicalName());
    }

    private static String physicalName(DdlContent.Table table, String columnId) {
        for (DdlContent.Column column : table.columns()) {
            if (column.id() != null && column.id().equals(columnId)) {
                return key(column.physicalName());
            }
        }
        return null;
    }

    /** 이름으로 제약·인덱스 찾기 — 대소문자 무시(카탈로그가 대소문자를 바꿔 돌려주는 계열 흡수) */
    private static <T> T findFirstByName(List<T> items, String name, java.util.function.Function<T, String> nameOf) {
        for (T item : items) {
            if (Objects.equals(key(nameOf.apply(item)), key(name))) {
                return item;
            }
        }
        return null;
    }

    private static DdlContent.KeyConstraint findKeyByName(List<DdlContent.KeyConstraint> constraints, String name) {
        return findFirstByName(constraints, name, DdlContent.KeyConstraint::name);
    }

    private static DdlContent.Index findIndexByName(List<DdlContent.Index> indexes, String name) {
        return findFirstByName(indexes, name, DdlContent.Index::name);
    }

    /** 제약 컬럼 목록 동등 — id를 물리명으로 바꿔 순서까지 비교한다. 둘 다 null이면 같다 */
    private static boolean sameColumnRefs(DdlContent.Table fromTable, DdlContent.KeyConstraint fromKey,
                                          DdlContent.Table toTable, DdlContent.KeyConstraint toKey) {
        List<String> fromNames = columnRefs(fromTable, fromKey);
        List<String> toNames = columnRefs(toTable, toKey);
        return Objects.equals(fromNames, toNames);
    }

    private static List<String> columnRefs(DdlContent.Table table, DdlContent.KeyConstraint constraint) {
        if (constraint == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String columnId : constraint.columnIds()) {
            String name = physicalName(table, columnId);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static boolean sameIndexColumns(DdlContent.Table fromTable, DdlContent.Index fromIndex,
                                            DdlContent.Table toTable, DdlContent.Index toIndex) {
        return Objects.equals(indexColumnRefs(fromTable, fromIndex), indexColumnRefs(toTable, toIndex));
    }

    /** 인덱스 컬럼 동등 — "물리명 정렬방향" 목록(정렬 미지정은 ASC로 본다) */
    private static List<String> indexColumnRefs(DdlContent.Table table, DdlContent.Index index) {
        List<String> refs = new ArrayList<>();
        for (DdlContent.IndexColumn indexColumn : index.columns()) {
            String name = physicalName(table, indexColumn.columnId());
            if (name != null) {
                String order = indexColumn.order() == null || indexColumn.order().isBlank()
                        ? "ASC" : indexColumn.order().trim().toUpperCase();
                refs.add(name + " " + order);
            }
        }
        return refs;
    }
}
