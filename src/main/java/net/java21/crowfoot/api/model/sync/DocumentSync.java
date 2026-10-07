package net.java21.crowfoot.api.model.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DB → 문서 동기화 병합 — 서버 포트 (05-editor/04-dbms-engineering.md §3.3, 08-core/02-model.md §1.16).
 *
 * <p>웹 에디터의 DB 동기화(crowfoot-web {@code sync-merge.ts})와 같은 규칙을 문서 JSON 트리에 직접 적용한다.
 * 웹은 변경 파이프라인(ErdChange)을 거치지만 서버는 DB 본체가 곧 목표 상태이므로 결과 문서를 바로 만든다.
 * 두 구현의 규칙은 같아야 한다 — 한쪽 규칙을 바꾸면 다른 쪽도 함께 바꾼다.
 *
 * <p>소유 규칙:
 * <ul>
 *   <li>DB가 정하는 값은 덮어쓴다 — 물리명으로 맞춘 테이블·컬럼의 타입·길이·정밀도·스케일·NULL·기본값·자동 증가·
 *       생성식·ON UPDATE, PK, UK, CHECK, 관계(FK)의 매핑·참조 동작·유형·기수.</li>
 *   <li>문서만 가진 값은 지킨다 — diagram(위치·색·메모·그룹·요구사항·검증 예외), 테이블·컬럼 {@code comment},
 *       도메인 타입 연결, 물리명 대소문자. 논리명은 DB 코멘트가 있을 때(DB 논리명 ≠ 물리명 — 리버스 조립 규칙)만 덮어쓴다.</li>
 *   <li>UK·CHECK는 DB 우선 전체 교체다(문서 전용 UK·CHECK는 지운다). 같은 이름이면 id를 이어 쓴다.</li>
 *   <li>인덱스는 DB에 있는 것을 이름으로 맞춰 추가·갱신한다. 문서에만 있는 인덱스는 지울 대상(removals)이다.</li>
 *   <li>문서에만 있는 테이블·컬럼·관계·인덱스는 지울 대상(removals)으로 따로 알리고, {@code includeRemovals=true}일 때만 지운다.</li>
 * </ul>
 *
 * <p>매칭 키: 테이블·컬럼 = 물리명(trim·소문자), 관계 = (자식, fkName) 우선 → (부모, 자식) 폴백 — 웹과 같다.
 * 새 객체는 UUID를 받고, 맞춘 객체는 id를 유지한다. 새 테이블에는 위치(diagram.nodes)를 주지 않는다 —
 * 에디터가 문서를 열 때 배치한다(08-core/02-model.md §1.5.1 "위치 없는 테이블").
 *
 * <p>멱등성(고정점): 결과 문서를 같은 DB 본체로 다시 동기화하면 items가 비어야 한다(테스트로 고정).
 * items·removals는 이름만 담는다(id 없음) — 같은 문서·같은 DB면 같은 목록이 나와 계획 지문이 같다.
 */
public final class DocumentSync {

    /** 변경 항목 — 웹 동기화 미리보기 목록과 같은 모양(kind·action·table·name·detail) */
    public record Item(String kind, String action, String table, String name, String detail) {
    }

    /** 동기화 결과 — items는 적용되는 변경, removals는 includeRemovals=true일 때만 적용되는 삭제 */
    public record Result(List<Item> items, List<Item> removals, ObjectNode merged) {

        public int changeCount() {
            return items.size();
        }

        /** 계획 지문 — items·removals의 정규 직렬화 SHA-256(16진수). 요청 옵션(includeRemovals)과 무관하다 */
        public String fingerprint() {
            return DocumentSync.fingerprint(items, removals);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** 컬럼 스칼라 비교 대상 — 웹 columnPatch와 같은 순서 */
    private static final List<String> COLUMN_FIELDS = List.of(
            "dataType", "length", "precision", "scale", "nullable", "autoIncrement", "defaultValue", "generated", "onUpdate",
            "identityGeneration");

    private DocumentSync() {
    }

    /**
     * 문서와 DB 본체(스키마 조회 조립 — introspectContentForComparison)를 병합한다. 입력은 바꾸지 않는다.
     *
     * @param includeRemovals true면 removals까지 적용한다. items·removals 목록 자체는 이 값과 무관하게 같다
     */
    public static Result sync(JsonNode document, JsonNode db, boolean includeRemovals) {
        return new Run(document, db, includeRemovals).run();
    }

    /** items·removals 정규 직렬화의 SHA-256 — 배열의 배열로 적어 키 순서에 흔들리지 않게 한다 */
    public static String fingerprint(List<Item> items, List<Item> removals) {
        ObjectNode canonical = NODES.objectNode();
        canonical.set("items", canonicalItems(items));
        canonical.set("removals", canonicalItems(removals));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ArrayNode canonicalItems(List<Item> items) {
        ArrayNode array = NODES.arrayNode();
        for (Item item : items) {
            array.addArray().add(item.kind()).add(item.action()).add(item.table()).add(item.name()).add(item.detail());
        }
        return array;
    }

    /* =====================================================================
     * 한 번의 동기화 — 상태를 들고 단계를 차례로 밟는다
     * ===================================================================== */

    private static final class Run {

        private final ObjectNode root;
        private final JsonNode db;
        private final boolean includeRemovals;
        private final ArrayNode tables;
        private final ArrayNode relationships;
        private final List<Item> items = new ArrayList<>();
        private final List<Item> removals = new ArrayList<>();
        /** 문서 전체 키 네임스페이스(PK·UK·인덱스·CHECK·FK 이름, 소문자 — 웹 keys.ts documentKeyNames) */
        private final Set<String> taken = new HashSet<>();
        /** 존 정렬(PK→FK→일반) 대상 — 컬럼·PK·관계가 바뀐 테이블 id */
        private final Set<String> changedTables = new LinkedHashSet<>();
        /** DB 컬럼 id → 병합 문서 컬럼 id (테이블마다 id가 다르니 전역 지도 하나로 충분하다) */
        private final Map<String, String> columnIdByDbId = new HashMap<>();
        /** DB 테이블 id → 병합 문서 테이블 */
        private final Map<String, ObjectNode> tableByDbId = new HashMap<>();
        /** includeRemovals일 때 지울 대상 */
        private final List<String> removeTableIds = new ArrayList<>();
        private final List<String> removeRelationshipIds = new ArrayList<>();
        private final List<String[]> removedColumns = new ArrayList<>();

        Run(JsonNode document, JsonNode db, boolean includeRemovals) {
            this.root = document != null && document.isObject() ? (ObjectNode) document.deepCopy() : NODES.objectNode();
            this.db = db;
            this.includeRemovals = includeRemovals;
            ObjectNode model = objectField(root, "model");
            this.tables = arrayField(model, "tables");
            this.relationships = arrayField(model, "relationships");
            for (JsonNode table : tables) {
                normalizeTable((ObjectNode) table);
            }
        }

        Result run() {
            collectTakenNames();
            List<JsonNode> dbTables = list(db.path("model").path("tables"));
            List<JsonNode> dbRelationships = list(db.path("model").path("relationships"));
            Map<String, JsonNode> dbTableByKey = new HashMap<>();
            Map<String, JsonNode> dbTableById = new HashMap<>();
            for (JsonNode table : dbTables) {
                dbTableByKey.putIfAbsent(key(text(table, "physicalName")), table);
                dbTableById.put(text(table, "id"), table);
            }

            // 1. 관계 매칭 — 원본 문서 기준(테이블·컬럼을 고치기 전에 매핑 이름쌍을 잡는다)
            List<RelPlan> relPlans = matchRelationships(dbRelationships, dbTableByKey, dbTableById);

            // 2. 테이블 — DB 순서대로 확보(추가) 후 컬럼·키를 DB에 맞춘다
            List<JsonNode> docOnlyTables = new ArrayList<>();
            for (JsonNode table : tables) {
                if (!dbTableByKey.containsKey(key(text(table, "physicalName")))) {
                    docOnlyTables.add(table);
                }
            }
            for (JsonNode dbTable : dbTables) {
                syncTable(dbTable);
            }

            // 3. 관계 — 매칭된 것은 DB 값으로 고치고, 새 것은 DB 컬럼 이름 그대로 매핑해 만든다
            for (RelPlan plan : relPlans) {
                syncRelationship(plan, dbTableById);
            }

            // 4. 문서에만 있는 테이블 — 지울 대상
            for (JsonNode table : docOnlyTables) {
                String name = text(table, "physicalName");
                removals.add(new Item("table", "remove", name, name, "not-in-db"));
                removeTableIds.add(text(table, "id"));
            }

            if (includeRemovals) {
                applyRemovals();
            }
            sortZones();
            return new Result(List.copyOf(items), List.copyOf(removals), root);
        }

        /* ---------- 관계 매칭 ---------- */

        private record RelPlan(JsonNode dbRel, ObjectNode docRel) {
        }

        private List<RelPlan> matchRelationships(List<JsonNode> dbRelationships, Map<String, JsonNode> dbTableByKey,
                                                 Map<String, JsonNode> dbTableById) {
            Map<String, List<JsonNode>> dbByChild = new HashMap<>();
            for (JsonNode rel : dbRelationships) {
                JsonNode child = dbTableById.get(text(rel, "childTableId"));
                if (child != null) {
                    dbByChild.computeIfAbsent(key(text(child, "physicalName")), k -> new ArrayList<>()).add(rel);
                }
            }
            Set<JsonNode> consumed = identitySet();
            // 매칭 대상 — 자식 테이블이 DB에 있는 문서 관계. 자식이 DB에 없으면 그 테이블 삭제(removals)가 관계를 함께 정리한다
            List<ObjectNode> candidates = new ArrayList<>();
            for (JsonNode node : relationships) {
                JsonNode child = docTableById(text(node, "childTableId"));
                if (child != null && dbTableByKey.containsKey(key(text(child, "physicalName")))) {
                    candidates.add((ObjectNode) node);
                }
            }
            // 1차 (자식, fkName) → 2차 (부모, 자식) 폴백. 1차를 모두 끝낸 뒤 폴백한다 — 앞선 관계가 폴백으로
            // 다른 관계의 정확한 짝을 가로채지 않게 한다(SchemaDiffer의 매칭과 같은 우선순위)
            Map<ObjectNode, JsonNode> matched = new java.util.IdentityHashMap<>();
            for (ObjectNode rel : candidates) {
                JsonNode child = docTableById(text(rel, "childTableId"));
                for (JsonNode candidate : dbByChild.getOrDefault(key(text(child, "physicalName")), List.of())) {
                    if (!consumed.contains(candidate) && key(text(candidate, "fkName")).equals(key(text(rel, "fkName")))) {
                        consumed.add(candidate);
                        matched.put(rel, candidate);
                        break;
                    }
                }
            }
            for (ObjectNode rel : candidates) {
                if (matched.containsKey(rel)) {
                    continue;
                }
                JsonNode child = docTableById(text(rel, "childTableId"));
                JsonNode parent = docTableById(text(rel, "parentTableId"));
                for (JsonNode candidate : dbByChild.getOrDefault(key(text(child, "physicalName")), List.of())) {
                    JsonNode dbParent = dbTableById.get(text(candidate, "parentTableId"));
                    if (!consumed.contains(candidate) && parent != null && dbParent != null
                            && key(text(dbParent, "physicalName")).equals(key(text(parent, "physicalName")))) {
                        consumed.add(candidate);
                        matched.put(rel, candidate);
                        break;
                    }
                }
            }
            List<RelPlan> plans = new ArrayList<>();
            for (ObjectNode rel : candidates) {
                JsonNode match = matched.get(rel);
                if (match == null) {
                    JsonNode child = docTableById(text(rel, "childTableId"));
                    removals.add(new Item("relationship", "remove", text(child, "physicalName"), text(rel, "fkName"), "not-in-db"));
                    removeRelationshipIds.add(text(rel, "id"));
                } else {
                    plans.add(new RelPlan(match, rel));
                }
            }
            for (JsonNode rel : dbRelationships) {
                if (!consumed.contains(rel)) {
                    plans.add(new RelPlan(rel, null));
                }
            }
            return plans;
        }

        /* ---------- 테이블 ---------- */

        private void syncTable(JsonNode dbTable) {
            String physicalName = text(dbTable, "physicalName");
            ObjectNode table = docTableByName(physicalName);
            boolean created = table == null;
            if (created) {
                table = NODES.objectNode();
                table.put("id", UUID.randomUUID().toString());
                table.put("logicalName", text(dbTable, "logicalName"));
                table.put("physicalName", physicalName);
                table.putNull("comment");
                table.putArray("columns");
                table.putNull("primaryKey");
                table.putArray("uniques");
                table.putArray("indexes");
                table.putArray("checks");
                tables.add(table);
                changedTables.add(text(table, "id"));
                items.add(new Item("table", "add", physicalName, physicalName,
                        "columns=" + dbTable.path("columns").size()));
            } else if (hasDbComment(dbTable) && !text(dbTable, "logicalName").equals(text(table, "logicalName"))) {
                // 테이블 논리명 — 컬럼과 같은 규칙(DB 코멘트가 있을 때만). comment(문서 설명)는 건드리지 않는다
                items.add(new Item("table", "update", physicalName, physicalName,
                        "logicalName: " + text(table, "logicalName") + " → " + text(dbTable, "logicalName")));
                table.put("logicalName", text(dbTable, "logicalName"));
            }
            tableByDbId.put(text(dbTable, "id"), table);
            String tableName = text(table, "physicalName");

            syncColumns(table, dbTable, tableName);
            syncPrimaryKey(table, dbTable, tableName);
            syncUniques(table, dbTable, tableName);
            syncIndexes(table, dbTable, tableName);
            syncChecks(table, dbTable, tableName);
        }

        private void syncColumns(ObjectNode table, JsonNode dbTable, String tableName) {
            String tableId = text(table, "id");
            Map<String, JsonNode> dbColumns = new LinkedHashMap<>();
            for (JsonNode column : dbTable.path("columns")) {
                dbColumns.putIfAbsent(key(text(column, "physicalName")), column);
            }
            ArrayNode columns = (ArrayNode) table.get("columns");
            Set<String> present = new HashSet<>();
            for (JsonNode node : columns) {
                ObjectNode column = (ObjectNode) node;
                String columnKey = key(text(column, "physicalName"));
                present.add(columnKey);
                JsonNode dbColumn = dbColumns.get(columnKey);
                if (dbColumn == null) {
                    removals.add(new Item("column", "remove", tableName, text(column, "physicalName"), "not-in-db"));
                    removedColumns.add(new String[] {tableId, text(column, "id")});
                    continue;
                }
                columnIdByDbId.put(text(dbColumn, "id"), text(column, "id"));
                List<String> fields = patchColumn(column, dbColumn);
                if (!fields.isEmpty()) {
                    items.add(new Item("column", "update", tableName, text(column, "physicalName"), String.join("; ", fields)));
                }
            }
            // DB에만 있는 컬럼 — 끝에 붙이고 존 정렬(PK→FK→일반)로 자리를 잡는다
            for (JsonNode dbColumn : dbColumns.values()) {
                if (present.contains(key(text(dbColumn, "physicalName")))) {
                    continue;
                }
                ObjectNode column = newColumn(dbColumn);
                columns.add(column);
                columnIdByDbId.put(text(dbColumn, "id"), text(column, "id"));
                changedTables.add(tableId);
                items.add(new Item("column", "add", tableName, text(dbColumn, "physicalName"), columnSpec(dbColumn)));
            }
        }

        /** 컬럼 스칼라 패치 — 웹 columnPatch와 같은 규칙. 바뀐 필드를 "필드: 이전 → 이후"로 돌려준다(값이 계획 지문에 들어간다) */
        private List<String> patchColumn(ObjectNode column, JsonNode dbColumn) {
            List<String> fields = new ArrayList<>();
            for (String field : COLUMN_FIELDS) {
                JsonNode current = value(column, field);
                JsonNode target = value(dbColumn, field);
                if ("defaultValue".equals(field)) {
                    // ''≡null 정규화 — 드리프트 방지
                    current = normDefault(current);
                    target = normDefault(target);
                }
                if (!Objects.equals(current, target)) {
                    fields.add(field + ": " + display(current) + " → " + display(target));
                    column.set(field, target.deepCopy());
                }
            }
            // 논리명 — DB 코멘트가 있을 때만 덮어쓴다. 없으면 문서 값(논리명 자동 추론값 포함)을 지킨다
            if (hasDbComment(dbColumn) && !text(dbColumn, "logicalName").equals(text(column, "logicalName"))) {
                fields.add("logicalName: " + text(column, "logicalName") + " → " + text(dbColumn, "logicalName"));
                column.put("logicalName", text(dbColumn, "logicalName"));
            }
            return fields;
        }

        private static ObjectNode newColumn(JsonNode dbColumn) {
            ObjectNode column = NODES.objectNode();
            column.put("id", UUID.randomUUID().toString());
            column.put("logicalName", text(dbColumn, "logicalName"));
            column.put("physicalName", text(dbColumn, "physicalName"));
            column.put("dataType", text(dbColumn, "dataType"));
            column.set("length", value(dbColumn, "length").deepCopy());
            column.set("precision", value(dbColumn, "precision").deepCopy());
            column.set("scale", value(dbColumn, "scale").deepCopy());
            column.put("nullable", dbColumn.path("nullable").asBoolean(true));
            column.set("defaultValue", normDefault(value(dbColumn, "defaultValue")).deepCopy());
            column.put("autoIncrement", dbColumn.path("autoIncrement").asBoolean(false));
            if (dbColumn.path("identityGeneration").isTextual()) {
                column.put("identityGeneration", dbColumn.path("identityGeneration").asText());
            }
            column.putNull("comment");
            column.set("generated", value(dbColumn, "generated").deepCopy());
            column.set("onUpdate", value(dbColumn, "onUpdate").deepCopy());
            return column;
        }

        /* ---------- PK·UK·인덱스·CHECK ---------- */

        private void syncPrimaryKey(ObjectNode table, JsonNode dbTable, String tableName) {
            JsonNode current = table.get("primaryKey");
            boolean hasCurrent = current != null && current.isObject();
            String currentName = hasCurrent ? text(current, "name") : null;
            JsonNode dbPk = dbTable.get("primaryKey");
            if (dbPk == null || !dbPk.isObject()) {
                if (hasCurrent) {
                    table.putNull("primaryKey");
                    taken.remove(lower(currentName));
                    changedTables.add(text(table, "id"));
                    items.add(new Item("primaryKey", "remove", tableName, currentName, "not-in-db"));
                }
                return;
            }
            List<String> columnIds = mapColumnIds(dbPk.path("columnIds"));
            if (columnIds == null) {
                return; // 참조 컬럼을 못 찾으면 PK를 건드리지 않는다(안전 방향 — 웹과 같다)
            }
            String dbName = text(dbPk, "name");
            String name;
            if (currentName != null && sameKeyName(currentName, dbName)) {
                name = currentName;
            } else {
                Set<String> others = new HashSet<>(taken);
                if (currentName != null) {
                    others.remove(lower(currentName));
                }
                name = nextName(others, dbName);
            }
            boolean same = hasCurrent && name.equals(currentName) && columnIds.equals(strings(current.path("columnIds")));
            if (same) {
                return;
            }
            ObjectNode primaryKey = table.putObject("primaryKey");
            primaryKey.put("name", name);
            ArrayNode ids = primaryKey.putArray("columnIds");
            columnIds.forEach(ids::add);
            if (currentName != null) {
                taken.remove(lower(currentName));
            }
            taken.add(lower(name));
            changedTables.add(text(table, "id"));
            items.add(new Item("primaryKey", hasCurrent ? "update" : "add", tableName, name,
                    columnNames(dbTable, dbPk.path("columnIds"))));
        }

        /** UK — DB 우선 전체 교체. 같은 이름(또는 충돌 회피 접미 이름)의 기존 UK는 id·이름을 이어 쓴다 */
        private void syncUniques(ObjectNode table, JsonNode dbTable, String tableName) {
            List<ObjectNode> own = objects(table.get("uniques"));
            own.forEach(u -> taken.remove(lower(text(u, "name"))));
            Set<ObjectNode> consumed = identitySet();
            ArrayNode next = NODES.arrayNode();
            for (JsonNode dbUnique : dbTable.path("uniques")) {
                List<String> columnIds = mapColumnIds(dbUnique.path("columnIds"));
                if (columnIds == null || columnIds.isEmpty()) {
                    continue;
                }
                String dbName = text(dbUnique, "name");
                ObjectNode match = matchByName(own, consumed, dbName);
                ObjectNode unique = NODES.objectNode();
                String name;
                if (match != null) {
                    consumed.add(match);
                    name = text(match, "name");
                    unique.put("id", text(match, "id"));
                    if (!columnIds.equals(strings(match.path("columnIds")))) {
                        items.add(new Item("uniqueKey", "update", tableName, name, columnNames(dbTable, dbUnique.path("columnIds"))));
                    }
                } else {
                    name = nextName(taken, dbName);
                    unique.put("id", UUID.randomUUID().toString());
                    items.add(new Item("uniqueKey", "add", tableName, name, columnNames(dbTable, dbUnique.path("columnIds"))));
                }
                taken.add(lower(name));
                unique.put("name", name);
                ArrayNode ids = unique.putArray("columnIds");
                columnIds.forEach(ids::add);
                next.add(unique);
            }
            for (ObjectNode unique : own) {
                if (!consumed.contains(unique)) {
                    items.add(new Item("uniqueKey", "remove", tableName, text(unique, "name"), "not-in-db"));
                }
            }
            table.set("uniques", next);
        }

        /** 인덱스 — DB 인덱스를 이름으로 맞춰 추가·갱신한다. 문서에만 있는 인덱스는 지울 대상이다.
         *  v1.37: 유니크·식 키·조건·INCLUDE·연산자 클래스도 맞춘다. 식·조건은 SchemaDiffer와 같은 정규화로 같으면 문서 원문을 지킨다 */
        private void syncIndexes(ObjectNode table, JsonNode dbTable, String tableName) {
            ArrayNode indexes = (ArrayNode) table.get("indexes");
            List<ObjectNode> own = objects(indexes);
            Set<ObjectNode> consumed = identitySet();
            for (JsonNode dbIndex : dbTable.path("indexes")) {
                ArrayNode columns = NODES.arrayNode();
                boolean mappable = true;
                for (JsonNode column : dbIndex.path("columns")) {
                    String id = columnIdByDbId.get(text(column, "columnId"));
                    if (id == null) {
                        mappable = false;
                        break;
                    }
                    ObjectNode columnNode = columns.addObject().put("columnId", id)
                            .put("order", "DESC".equals(text(column, "order")) ? "DESC" : "ASC");
                    if (column.path("opclass").isString()) {
                        columnNode.put("opclass", text(column, "opclass"));
                    }
                }
                ArrayNode include = NODES.arrayNode();
                for (JsonNode id : dbIndex.path("include")) {
                    String mapped = columnIdByDbId.get(id.asString(""));
                    if (mapped == null) {
                        mappable = false;
                        break;
                    }
                    include.add(mapped);
                }
                String expression = dbIndex.path("expression").isString() ? text(dbIndex, "expression") : null;
                if (!mappable || (columns.isEmpty() && expression == null)) {
                    continue;
                }
                String type = dbIndex.path("type").isString() ? text(dbIndex, "type") : "BTREE";
                JsonNode parser = value(dbIndex, "parser");
                String where = dbIndex.path("where").isString() ? text(dbIndex, "where") : null;
                boolean unique = dbIndex.path("unique").asBoolean(false);
                String detail = indexColumnNames(dbTable, dbIndex);
                ObjectNode match = matchByName(own, consumed, text(dbIndex, "name"));
                if (match != null) {
                    consumed.add(match);
                    String currentType = match.path("type").isString() ? text(match, "type") : "BTREE";
                    // 식·조건은 같은 뜻이면 문서 원문을 지킨다(DB는 캐스트를 붙여 다시 쓴다)
                    String keptExpression = sameExpression(match, "expression", expression) ? textOrNull(match, "expression") : expression;
                    String keptWhere = sameExpression(match, "where", where) ? textOrNull(match, "where") : where;
                    if (!columns.equals(match.get("columns")) || !type.equals(currentType)
                            || !Objects.equals(parser, value(match, "parser"))
                            || unique != match.path("unique").asBoolean(false)
                            || !Objects.equals(keptExpression, textOrNull(match, "expression"))
                            || !Objects.equals(keptWhere, textOrNull(match, "where"))
                            || !include.equals(match.path("include").isArray() ? match.get("include") : NODES.arrayNode())) {
                        writeIndex(match, columns, type, parser, unique, keptExpression, keptWhere, include);
                        items.add(new Item("index", "update", tableName, text(match, "name"), detail));
                    }
                    continue;
                }
                String name = nextName(taken, text(dbIndex, "name"));
                taken.add(lower(name));
                ObjectNode index = indexes.addObject();
                index.put("id", UUID.randomUUID().toString());
                index.put("name", name);
                writeIndex(index, columns, type, parser, unique, expression, where, include);
                items.add(new Item("index", "add", tableName, name, detail));
            }
            for (ObjectNode index : own) {
                if (!consumed.contains(index)) {
                    removals.add(new Item("index", "remove", tableName, text(index, "name"), "not-in-db"));
                    if (includeRemovals) {
                        removeIdentical(indexes, index);
                    }
                }
            }
        }

        /** 인덱스 속성 쓰기 — v1.37 속성은 값이 있을 때만 둔다(없는 인덱스는 이전과 같은 모양) */
        private void writeIndex(ObjectNode index, ArrayNode columns, String type, JsonNode parser, boolean unique,
                                String expression, String where, ArrayNode include) {
            index.set("columns", columns);
            index.put("type", type);
            index.set("parser", parser.deepCopy());
            if (unique) {
                index.put("unique", true);
            } else {
                index.remove("unique");
            }
            if (expression != null) {
                index.put("expression", expression);
            } else {
                index.remove("expression");
            }
            if (where != null) {
                index.put("where", where);
            } else {
                index.remove("where");
            }
            if (include.isEmpty()) {
                index.remove("include");
            } else {
                index.set("include", include);
            }
        }

        /** CHECK — UK와 같이 DB 우선 전체 교체. 식은 SchemaDiffer와 같은 정규화로 같으면 문서 원문을 지킨다 */
        private void syncChecks(ObjectNode table, JsonNode dbTable, String tableName) {
            List<ObjectNode> own = objects(table.get("checks"));
            own.forEach(c -> taken.remove(lower(text(c, "name"))));
            Set<ObjectNode> consumed = identitySet();
            ArrayNode next = NODES.arrayNode();
            for (JsonNode dbCheck : dbTable.path("checks")) {
                String dbName = text(dbCheck, "name");
                String expression = text(dbCheck, "expression");
                ObjectNode match = matchByName(own, consumed, dbName);
                ObjectNode check = NODES.objectNode();
                String name;
                if (match != null) {
                    consumed.add(match);
                    name = text(match, "name");
                    check.put("id", text(match, "id"));
                    if (Objects.equals(expressionKey(text(match, "expression")), expressionKey(expression))) {
                        expression = text(match, "expression");
                    } else {
                        items.add(new Item("check", "update", tableName, name, "expression: " + text(match, "expression") + " → " + expression));
                    }
                } else {
                    name = nextName(taken, dbName);
                    check.put("id", UUID.randomUUID().toString());
                    items.add(new Item("check", "add", tableName, name, expression));
                }
                taken.add(lower(name));
                check.put("name", name);
                check.put("expression", expression);
                next.add(check);
            }
            for (ObjectNode check : own) {
                if (!consumed.contains(check)) {
                    items.add(new Item("check", "remove", tableName, text(check, "name"), "not-in-db"));
                }
            }
            table.set("checks", next);
        }

        /* ---------- 관계 ---------- */

        private void syncRelationship(RelPlan plan, Map<String, JsonNode> dbTableById) {
            JsonNode dbRel = plan.dbRel();
            ObjectNode parent = tableByDbId.get(text(dbRel, "parentTableId"));
            ObjectNode child = tableByDbId.get(text(dbRel, "childTableId"));
            if (parent == null || child == null || dbRel.path("columnMappings").isEmpty()) {
                return;
            }
            // DB 컬럼 id → 병합 문서 컬럼 id — DB 컬럼은 2단계에서 모두 확보됐다(FK 컬럼을 새로 만들지 않고 재사용한다)
            ArrayNode mappings = NODES.arrayNode();
            for (JsonNode mapping : dbRel.path("columnMappings")) {
                String parentColumnId = columnIdByDbId.get(text(mapping, "parentColumnId"));
                String childColumnId = columnIdByDbId.get(text(mapping, "childColumnId"));
                if (parentColumnId == null || childColumnId == null) {
                    return;
                }
                mappings.addObject().put("parentColumnId", parentColumnId).put("childColumnId", childColumnId);
            }
            String childName = text(child, "physicalName");
            String fkName = text(dbRel, "fkName");
            ObjectNode rel = plan.docRel();
            if (rel == null) {
                rel = relationships.addObject();
                rel.put("id", UUID.randomUUID().toString());
                rel.put("name", text(dbRel, "name").isEmpty() ? fkName : text(dbRel, "name"));
                rel.put("parentTableId", text(parent, "id"));
                rel.put("childTableId", text(child, "id"));
                rel.put("type", text(dbRel, "type"));
                rel.put("identifying", dbRel.path("identifying").asBoolean(false));
                rel.put("parentMultiplicity", text(dbRel, "parentMultiplicity"));
                rel.put("childMultiplicity", text(dbRel, "childMultiplicity"));
                rel.put("fkName", fkName);
                rel.set("columnMappings", mappings);
                rel.put("onDelete", text(dbRel, "onDelete"));
                rel.put("onUpdate", text(dbRel, "onUpdate"));
                taken.add(lower(fkName));
                changedTables.add(text(child, "id"));
                items.add(new Item("relationship", "add", childName, fkName,
                        text(parent, "physicalName") + " ← " + childName));
                return;
            }
            List<String> fields = new ArrayList<>();
            // 매핑이 달라졌으면(컬럼 쌍) DB 매핑으로 바꾼다 — 웹은 제거 후 재생성, 서버는 id를 지키며 교체한다
            List<String> currentPairs = mappingPairs(rel);
            List<String> dbPairs = dbMappingPairs(dbRel, dbTableById);
            if (!currentPairs.equals(dbPairs)) {
                fields.add("columnMappings: " + String.join(", ", currentPairs) + " → " + String.join(", ", dbPairs));
                rel.set("columnMappings", mappings);
            } else if (!mappings.equals(rel.get("columnMappings"))) {
                rel.set("columnMappings", mappings); // 이름쌍은 같고 id만 다른 경우(같은 이름 컬럼 재사용) — 표현만 맞춘다
            }
            if (!text(rel, "parentTableId").equals(text(parent, "id"))) {
                fields.add("parent: " + text(docTableById(text(rel, "parentTableId")), "physicalName") + " → " + text(parent, "physicalName"));
                rel.put("parentTableId", text(parent, "id"));
            }
            for (String field : List.of("name", "fkName", "type", "identifying", "parentMultiplicity", "childMultiplicity",
                    "onDelete", "onUpdate")) {
                JsonNode target = value(dbRel, field);
                if ("name".equals(field) && (target.isNull() || target.asString("").isEmpty())) {
                    continue;
                }
                if (!Objects.equals(value(rel, field), target)) {
                    if ("fkName".equals(field)) {
                        taken.remove(lower(text(rel, "fkName")));
                        taken.add(lower(target.asString("")));
                    }
                    fields.add(field + ": " + display(value(rel, field)) + " → " + display(target));
                    rel.set(field, target.deepCopy());
                }
            }
            if (!fields.isEmpty()) {
                changedTables.add(text(child, "id"));
                items.add(new Item("relationship", "update", childName, fkName, String.join("; ", fields)));
            }
        }

        /** 문서 관계의 매핑 이름쌍(부모=자식, 물리명 소문자) — 원본 문서 기준(컬럼을 지우기 전이라 항상 찾는다) */
        private List<String> mappingPairs(JsonNode rel) {
            JsonNode parent = docTableById(text(rel, "parentTableId"));
            JsonNode child = docTableById(text(rel, "childTableId"));
            List<String> pairs = new ArrayList<>();
            for (JsonNode mapping : rel.path("columnMappings")) {
                pairs.add(columnKey(parent, text(mapping, "parentColumnId")) + "=" + columnKey(child, text(mapping, "childColumnId")));
            }
            return pairs;
        }

        private static List<String> dbMappingPairs(JsonNode rel, Map<String, JsonNode> dbTableById) {
            JsonNode parent = dbTableById.get(text(rel, "parentTableId"));
            JsonNode child = dbTableById.get(text(rel, "childTableId"));
            List<String> pairs = new ArrayList<>();
            for (JsonNode mapping : rel.path("columnMappings")) {
                pairs.add(columnKey(parent, text(mapping, "parentColumnId")) + "=" + columnKey(child, text(mapping, "childColumnId")));
            }
            return pairs;
        }

        private static String columnKey(JsonNode table, String columnId) {
            if (table != null) {
                for (JsonNode column : table.path("columns")) {
                    if (columnId.equals(text(column, "id"))) {
                        return key(text(column, "physicalName"));
                    }
                }
            }
            return "?";
        }

        /* ---------- 삭제(includeRemovals) ---------- */

        /** 문서에만 있는 관계·컬럼·테이블을 지운다. 그 객체를 가리키던 diagram 참조(위치·메모·그룹·요구사항·검증 예외)도 정리한다 */
        private void applyRemovals() {
            Set<String> goneTargets = new HashSet<>();
            Set<String> relIds = new HashSet<>(removeRelationshipIds);
            Set<String> tableIds = new HashSet<>(removeTableIds);
            for (JsonNode rel : relationships) {
                if (tableIds.contains(text(rel, "parentTableId")) || tableIds.contains(text(rel, "childTableId"))) {
                    relIds.add(text(rel, "id"));
                }
            }
            // 지우는 컬럼을 매핑에 쓰는 관계 — 문서 전용 관계라 이미 지울 대상이다. 남은 경우를 막는 방어
            for (String[] removed : removedColumns) {
                for (JsonNode rel : relationships) {
                    for (JsonNode mapping : rel.path("columnMappings")) {
                        if ((removed[0].equals(text(rel, "childTableId")) && removed[1].equals(text(mapping, "childColumnId")))
                                || (removed[0].equals(text(rel, "parentTableId")) && removed[1].equals(text(mapping, "parentColumnId")))) {
                            relIds.add(text(rel, "id"));
                        }
                    }
                }
            }
            for (int i = relationships.size() - 1; i >= 0; i--) {
                String id = text(relationships.get(i), "id");
                if (relIds.contains(id)) {
                    taken.remove(lower(text(relationships.get(i), "fkName")));
                    relationships.remove(i);
                    goneTargets.add("relationship:" + id);
                }
            }
            for (String[] removed : removedColumns) {
                ObjectNode table = docTableById(removed[0]);
                if (table == null) {
                    continue;
                }
                removeWhere((ArrayNode) table.get("columns"), column -> removed[1].equals(text(column, "id")));
                // PK·UK는 DB 값으로 이미 바뀌었다. 인덱스는 DB 인덱스(DB 컬럼만 참조)와 지운 문서 전용 인덱스뿐이다 — 방어로 참조를 거른다
                for (JsonNode index : table.get("indexes")) {
                    removeWhere((ArrayNode) index.get("columns"), column -> removed[1].equals(text(column, "columnId")));
                    if (index.path("include").isArray()) {
                        removeWhere((ArrayNode) index.get("include"), id -> removed[1].equals(id.asString("")));
                        if (index.path("include").isEmpty()) {
                            ((ObjectNode) index).remove("include");
                        }
                    }
                }
                removeWhere((ArrayNode) table.get("indexes"), index -> index.path("columns").isEmpty() && !index.hasNonNull("expression"));
                goneTargets.add("column:" + removed[0] + ":" + removed[1]);
                changedTables.add(removed[0]);
            }
            for (int i = tables.size() - 1; i >= 0; i--) {
                String id = text(tables.get(i), "id");
                if (tableIds.contains(id)) {
                    tables.remove(i);
                    goneTargets.add("table:" + id);
                    changedTables.remove(id);
                }
            }
            JsonNode diagram = root.path("diagram");
            if (diagram.isObject()) {
                if (diagram.path("nodes").isObject()) {
                    tableIds.forEach(((ObjectNode) diagram.get("nodes"))::remove);
                }
                for (JsonNode note : diagram.path("notes")) {
                    if (note.isObject() && tableIds.contains(note.path("linkedTableId").asString(""))) {
                        ((ObjectNode) note).putNull("linkedTableId");
                    }
                }
                for (String owners : List.of("areas", "requirements")) {
                    for (JsonNode owner : diagram.path(owners)) {
                        if (owner.path("tableIds").isArray()) {
                            removeWhere((ArrayNode) owner.get("tableIds"), id -> tableIds.contains(id.asString("")));
                        }
                    }
                }
                if (diagram.path("validationExceptions").isArray()) {
                    removeWhere((ArrayNode) diagram.get("validationExceptions"), exception -> {
                        String target = exception.path("target").asString("");
                        return goneTargets.contains(target)
                                || tableIds.stream().anyMatch(id -> target.startsWith("column:" + id + ":"));
                    });
                }
            }
        }

        /* ---------- 존 정렬 ---------- */

        /** 바뀐 테이블의 컬럼을 PK → FK → 일반 순서로 안정 정렬한다(웹 6단계와 같다) */
        private void sortZones() {
            for (String tableId : changedTables) {
                ObjectNode table = docTableById(tableId);
                if (table == null) {
                    continue;
                }
                Set<String> pk = new HashSet<>(table.get("primaryKey") != null && table.get("primaryKey").isObject()
                        ? strings(table.get("primaryKey").path("columnIds")) : List.of());
                Set<String> fk = new HashSet<>();
                for (JsonNode rel : relationships) {
                    if (tableId.equals(text(rel, "childTableId"))) {
                        rel.path("columnMappings").forEach(m -> fk.add(text(m, "childColumnId")));
                    }
                }
                List<JsonNode> columns = list(table.get("columns"));
                List<JsonNode> sorted = new ArrayList<>(columns);
                sorted.sort((a, b) -> Integer.compare(rank(a, pk, fk), rank(b, pk, fk)));
                if (!sorted.equals(columns)) {
                    ArrayNode array = table.putArray("columns");
                    sorted.forEach(array::add);
                }
            }
        }

        private static int rank(JsonNode column, Set<String> pk, Set<String> fk) {
            String id = text(column, "id");
            return pk.contains(id) ? 0 : fk.contains(id) ? 1 : 2;
        }

        /* ---------- 찾기·도우미 ---------- */

        private void collectTakenNames() {
            for (JsonNode table : tables) {
                JsonNode pk = table.get("primaryKey");
                if (pk != null && pk.isObject()) {
                    taken.add(lower(text(pk, "name")));
                }
                for (String field : List.of("uniques", "indexes", "checks")) {
                    table.path(field).forEach(k -> taken.add(lower(text(k, "name"))));
                }
            }
            relationships.forEach(rel -> taken.add(lower(text(rel, "fkName"))));
        }

        private ObjectNode docTableByName(String physicalName) {
            for (JsonNode table : tables) {
                if (key(text(table, "physicalName")).equals(key(physicalName))) {
                    return (ObjectNode) table;
                }
            }
            return null;
        }

        private ObjectNode docTableById(String id) {
            for (JsonNode table : tables) {
                if (id.equals(text(table, "id"))) {
                    return (ObjectNode) table;
                }
            }
            return null;
        }

        /** DB 컬럼 id 목록 → 병합 문서 컬럼 id 목록. 하나라도 못 찾으면 null */
        private List<String> mapColumnIds(JsonNode dbColumnIds) {
            List<String> ids = new ArrayList<>();
            for (JsonNode id : dbColumnIds) {
                String mapped = columnIdByDbId.get(id.asString(""));
                if (mapped == null) {
                    return null;
                }
                ids.add(mapped);
            }
            return ids;
        }
    }

    /* =====================================================================
     * 공용 도우미
     * ===================================================================== */

    /** 키 이름 매칭 — 같은 이름(대소문자 무시) 우선, 없으면 충돌 회피 접미 이름({@code 이름_N} — keys.ts nextName) */
    private static ObjectNode matchByName(List<ObjectNode> own, Set<ObjectNode> consumed, String dbName) {
        for (ObjectNode candidate : own) {
            if (!consumed.contains(candidate) && lower(text(candidate, "name")).equals(lower(dbName))) {
                return candidate;
            }
        }
        Pattern suffixed = Pattern.compile(Pattern.quote(lower(dbName)) + "_\\d+");
        for (ObjectNode candidate : own) {
            if (!consumed.contains(candidate) && suffixed.matcher(lower(text(candidate, "name"))).matches()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean sameKeyName(String current, String dbName) {
        return lower(current).equals(lower(dbName)) || lower(current).matches(Pattern.quote(lower(dbName)) + "_\\d+");
    }

    private static <T> Set<T> identitySet() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    /** 충돌 회피 접미 — base가 있으면 {@code _1}, {@code _2}… (웹 keys.ts nextName과 같다) */
    static String nextName(Set<String> existing, String base) {
        if (!existing.contains(lower(base))) {
            return base;
        }
        for (int i = 1; ; i++) {
            String candidate = base + "_" + i;
            if (!existing.contains(lower(candidate))) {
                return candidate;
            }
        }
    }

    /** DB 논리명에 코멘트가 실려 있는가 — 리버스 조립 규칙상 코멘트가 없으면 논리명 = 물리명(빈 문자열도 없음 취급) */
    private static boolean hasDbComment(JsonNode node) {
        String logicalName = text(node, "logicalName");
        return !logicalName.isEmpty() && !logicalName.equals(text(node, "physicalName"));
    }

    /** 인덱스 식·조건이 같은 뜻인지 — SchemaDiffer.expressionKey로 비교한다(캐스트·괄호·공백·대소문자 무시) */
    private static boolean sameExpression(JsonNode node, String field, String dbValue) {
        return Objects.equals(net.java21.crowfoot.api.model.ddl.SchemaDiffer.expressionKey(textOrNull(node, field)),
                net.java21.crowfoot.api.model.ddl.SchemaDiffer.expressionKey(dbValue));
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.path(field).isString() ? node.path(field).asString() : null;
    }

    /** CHECK 식 비교 키 — SchemaDiffer.expressionKey와 같은 정규화(괄호·백틱·큰따옴표·공백·대소문자 무시) */
    private static String expressionKey(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        return expression.replaceAll("[()`\"\\s]", "").toLowerCase(Locale.ROOT);
    }

    /** 항목 상세의 값 표기 — null은 "null", 생성식은 "식 (STORED|VIRTUAL)" */
    private static String display(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "null";
        }
        if (value.isObject() && value.has("expression")) {
            return text(value, "expression") + (value.path("stored").asBoolean(false) ? " (STORED)" : " (VIRTUAL)");
        }
        return value.isString() ? value.asString() : value.toString();
    }

    /** 새 컬럼 표기 — 타입(길이·정밀도)과 NOT NULL */
    private static String columnSpec(JsonNode column) {
        StringBuilder spec = new StringBuilder(text(column, "dataType"));
        JsonNode length = value(column, "length");
        JsonNode precision = value(column, "precision");
        JsonNode scale = value(column, "scale");
        if (!length.isNull()) {
            spec.append('(').append(length.asInt()).append(')');
        } else if (!precision.isNull()) {
            spec.append('(').append(precision.asInt());
            if (!scale.isNull()) {
                spec.append(',').append(scale.asInt());
            }
            spec.append(')');
        }
        if (!column.path("nullable").asBoolean(true)) {
            spec.append(" NOT NULL");
        }
        return spec.toString();
    }

    private static String columnNames(JsonNode dbTable, JsonNode columnIds) {
        List<String> names = new ArrayList<>();
        for (JsonNode id : columnIds) {
            names.add(dbColumnName(dbTable, id.asString("")));
        }
        return String.join(", ", names);
    }

    private static String indexColumnNames(JsonNode dbTable, JsonNode dbIndex) {
        List<String> names = new ArrayList<>();
        for (JsonNode column : dbIndex.path("columns")) {
            String name = dbColumnName(dbTable, text(column, "columnId"));
            names.add("DESC".equals(text(column, "order")) ? name + " DESC" : name);
        }
        String type = dbIndex.path("type").isString() ? text(dbIndex, "type") : "BTREE";
        String keys = dbIndex.path("expression").isString() ? text(dbIndex, "expression") : String.join(", ", names);
        String where = dbIndex.path("where").isString() ? " WHERE " + text(dbIndex, "where") : "";
        String unique = dbIndex.path("unique").asBoolean(false) ? "UNIQUE " : "";
        return unique + ("BTREE".equals(type) ? keys : type + " " + keys) + where;
    }

    private static String dbColumnName(JsonNode dbTable, String columnId) {
        for (JsonNode column : dbTable.path("columns")) {
            if (columnId.equals(text(column, "id"))) {
                return text(column, "physicalName");
            }
        }
        return columnId;
    }

    /** 옛 문서에는 uniques·indexes·checks가 없을 수 있다 — 에디터가 열 때 하는 정규화와 같다 */
    private static void normalizeTable(ObjectNode table) {
        for (String field : List.of("columns", "uniques", "indexes", "checks")) {
            if (!(table.get(field) instanceof ArrayNode)) {
                table.putArray(field);
            }
        }
    }

    private static ObjectNode objectField(ObjectNode node, String field) {
        if (!(node.get(field) instanceof ObjectNode)) {
            node.putObject(field);
        }
        return (ObjectNode) node.get(field);
    }

    private static ArrayNode arrayField(ObjectNode node, String field) {
        if (!(node.get(field) instanceof ArrayNode)) {
            node.putArray(field);
        }
        return (ArrayNode) node.get(field);
    }

    /** 필드 값 — 없으면 null 노드(없음 ≡ null) */
    private static JsonNode value(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isMissingNode() ? NullNode.getInstance() : value;
    }

    private static JsonNode normDefault(JsonNode value) {
        return value.isNull() || (value.isString() && value.asString().isEmpty()) ? NullNode.getInstance() : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asString("");
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String lower(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null) {
            array.forEach(out::add);
        }
        return out;
    }

    private static List<ObjectNode> objects(JsonNode array) {
        List<ObjectNode> out = new ArrayList<>();
        if (array != null) {
            for (JsonNode node : array) {
                if (node instanceof ObjectNode object) {
                    out.add(object);
                }
            }
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(value -> out.add(value.asString("")));
        }
        return out;
    }

    private static void removeIdentical(ArrayNode array, JsonNode target) {
        for (int i = array.size() - 1; i >= 0; i--) {
            if (array.get(i) == target) {
                array.remove(i);
            }
        }
    }

    private static void removeWhere(ArrayNode array, java.util.function.Predicate<JsonNode> match) {
        for (int i = array.size() - 1; i >= 0; i--) {
            if (match.test(array.get(i))) {
                array.remove(i);
            }
        }
    }
}
