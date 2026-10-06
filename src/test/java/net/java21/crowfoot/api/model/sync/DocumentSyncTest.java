package net.java21.crowfoot.api.model.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DB → 문서 동기화 병합 — 소유 규칙·매칭·삭제 옵션·멱등성 (05-editor/04-dbms-engineering.md §3.3, 08-core/02-model.md §1.16).
 *
 * <p>DB 본체는 서버가 새로 조립한 것이라 객체 id가 문서와 전혀 다르다 — 매칭이 물리명 기준임을 전제로 id를 전부 다르게 준다.
 * 웹 sync-merge.test.ts와 같은 픽스처 모양(users·orders)을 쓴다.
 */
class DocumentSyncTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /* ---------- 빌더 ---------- */

    private static ObjectNode col(String id, String physicalName, String dataType) {
        ObjectNode column = JSON.createObjectNode();
        column.put("id", id);
        column.put("logicalName", physicalName);
        column.put("physicalName", physicalName);
        column.put("dataType", dataType);
        column.putNull("length");
        column.putNull("precision");
        column.putNull("scale");
        column.put("nullable", true);
        column.putNull("defaultValue");
        column.put("autoIncrement", false);
        column.putNull("comment");
        column.putNull("generated");
        column.putNull("onUpdate");
        return column;
    }

    private static ObjectNode table(String id, String physicalName, String pkName, ObjectNode... columns) {
        ObjectNode table = JSON.createObjectNode();
        table.put("id", id);
        table.put("logicalName", physicalName);
        table.put("physicalName", physicalName);
        table.putNull("comment");
        ArrayNode array = table.putArray("columns");
        for (ObjectNode column : columns) {
            array.add(column);
        }
        if (pkName == null) {
            table.putNull("primaryKey");
        } else {
            table.putObject("primaryKey").put("name", pkName).putArray("columnIds").add(columns[0].get("id").asString());
        }
        table.putArray("uniques");
        table.putArray("indexes");
        table.putArray("checks");
        return table;
    }

    private static ObjectNode rel(String id, String fkName, ObjectNode parent, ObjectNode child, String parentColumnId,
                                  String childColumnId) {
        ObjectNode rel = JSON.createObjectNode();
        rel.put("id", id);
        rel.put("name", fkName);
        rel.put("parentTableId", parent.get("id").asString());
        rel.put("childTableId", child.get("id").asString());
        rel.put("type", "ONE_TO_MANY");
        rel.put("identifying", false);
        rel.put("parentMultiplicity", "ZERO_OR_ONE");
        rel.put("childMultiplicity", "ONE_OR_MORE");
        rel.put("fkName", fkName);
        rel.putArray("columnMappings").addObject().put("parentColumnId", parentColumnId).put("childColumnId", childColumnId);
        rel.put("onDelete", "NO_ACTION");
        rel.put("onUpdate", "NO_ACTION");
        return rel;
    }

    private static ObjectNode doc(List<ObjectNode> tables, List<ObjectNode> relationships) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        ObjectNode model = root.putObject("model");
        ArrayNode tableArray = model.putArray("tables");
        tables.forEach(tableArray::add);
        ArrayNode relArray = model.putArray("relationships");
        relationships.forEach(relArray::add);
        ObjectNode diagram = root.putObject("diagram");
        diagram.putObject("nodes");
        diagram.putArray("notes");
        diagram.putArray("areas");
        diagram.putArray("requirements");
        diagram.putArray("validationExceptions");
        diagram.putNull("viewport");
        return root;
    }

    /* ---------- 픽스처 — 문서(t-*)와 DB(db-*) ---------- */

    private static ObjectNode docUsers() {
        ObjectNode id = col("t-c-users-id", "id", "BIGINT").put("nullable", false).put("autoIncrement", true);
        return table("t-users", "users", "users_pk", id);
    }

    private static ObjectNode docOrders() {
        ObjectNode orders = table("t-orders", "orders", "orders_pk",
                col("t-c-orders-id", "id", "BIGINT").put("nullable", false).put("autoIncrement", true),
                col("t-c-orders-user-id", "user_id", "BIGINT").put("logicalName", "주문자"),
                col("t-c-orders-status", "status", "VARCHAR").put("length", 20).put("nullable", false).put("defaultValue", ""),
                col("t-c-orders-memo", "memo", "VARCHAR").put("length", 100).put("comment", "내부 메모"));
        orders.put("comment", "팀 공유 메모");
        return orders;
    }

    private static ObjectNode dbUsers() {
        ObjectNode id = col("db-c-users-id", "id", "BIGINT").put("nullable", false).put("autoIncrement", true);
        return table("db-t-users", "users", "users_pk", id);
    }

    private static ObjectNode dbOrders() {
        return table("db-t-orders", "orders", "orders_pk",
                col("db-c-orders-id", "id", "BIGINT").put("nullable", false).put("autoIncrement", true),
                col("db-c-orders-user-id", "user_id", "BIGINT"),
                col("db-c-orders-status", "status", "VARCHAR").put("length", 20).put("nullable", false),
                col("db-c-orders-memo", "memo", "VARCHAR").put("length", 100));
    }

    private static ObjectNode docRel() {
        return rel("r-orders-users", "fk_orders_users", docUsers(), docOrders(), "t-c-users-id", "t-c-orders-user-id");
    }

    private static ObjectNode dbRel() {
        return rel("db-r-orders-users", "fk_orders_users", dbUsers(), dbOrders(), "db-c-users-id", "db-c-orders-user-id");
    }

    /* ---------- 조회 도우미 ---------- */

    private static JsonNode tableNamed(JsonNode root, String name) {
        for (JsonNode table : root.path("model").path("tables")) {
            if (name.equals(table.path("physicalName").asString())) {
                return table;
            }
        }
        return null;
    }

    private static JsonNode columnNamed(JsonNode table, String name) {
        for (JsonNode column : table.path("columns")) {
            if (name.equals(column.path("physicalName").asString())) {
                return column;
            }
        }
        return null;
    }

    private static List<String> describe(List<DocumentSync.Item> items) {
        List<String> out = new ArrayList<>();
        items.forEach(item -> out.add(item.kind() + "/" + item.action() + " " + item.table() + "." + item.name()));
        return out;
    }

    /* ---------- 시험 ---------- */

    @Test
    @DisplayName("같은 구조면 변경이 없다 — defaultValue는 ''≡null, 코멘트 없는 DB 논리명은 문서 논리명을 덮지 않는다")
    void noChangesWhenSame() {
        DocumentSync.Result result = DocumentSync.sync(doc(List.of(docUsers(), docOrders()), List.of(docRel())),
                doc(List.of(dbUsers(), dbOrders()), List.of(dbRel())), false);

        assertThat(result.items()).isEmpty();
        assertThat(result.removals()).isEmpty();
        assertThat(columnNamed(tableNamed(result.merged(), "orders"), "user_id").path("logicalName").asString()).isEqualTo("주문자");
    }

    @Test
    @DisplayName("컬럼 타입·길이가 바뀌면 DB 값으로 덮고 컬럼 id·comment는 지킨다")
    void columnTypeChange() {
        ObjectNode dbOrders = dbOrders();
        ((ObjectNode) dbOrders.get("columns").get(3)).put("dataType", "TEXT").putNull("length");
        ((ObjectNode) dbOrders.get("columns").get(2)).put("length", 50);

        DocumentSync.Result result = DocumentSync.sync(doc(List.of(docUsers(), docOrders()), List.of(docRel())),
                doc(List.of(dbUsers(), dbOrders), List.of(dbRel())), false);

        assertThat(result.items()).extracting(DocumentSync.Item::name, DocumentSync.Item::detail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("status", "length: 20 → 50"),
                        org.assertj.core.groups.Tuple.tuple("memo", "dataType: VARCHAR → TEXT; length: 100 → null"));
        JsonNode memo = columnNamed(tableNamed(result.merged(), "orders"), "memo");
        assertThat(memo.path("id").asString()).isEqualTo("t-c-orders-memo");
        assertThat(memo.path("dataType").asString()).isEqualTo("TEXT");
        assertThat(memo.path("length").isNull()).isTrue();
        assertThat(memo.path("comment").asString()).isEqualTo("내부 메모");
        assertThat(columnNamed(tableNamed(result.merged(), "orders"), "status").path("length").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("DB에 더한 컬럼은 새 id로 끝에 붙는다")
    void addedColumn() {
        ObjectNode dbOrders = dbOrders();
        ((ArrayNode) dbOrders.get("columns")).add(col("db-c-orders-paid", "paid_at", "DATETIME"));

        DocumentSync.Result result = DocumentSync.sync(doc(List.of(docUsers(), docOrders()), List.of(docRel())),
                doc(List.of(dbUsers(), dbOrders), List.of(dbRel())), false);

        assertThat(describe(result.items())).containsExactly("column/add orders.paid_at");
        JsonNode paid = columnNamed(tableNamed(result.merged(), "orders"), "paid_at");
        assertThat(paid.path("id").asString()).isNotEqualTo("db-c-orders-paid").hasSize(36);
        assertThat(paid.path("comment").isNull()).isTrue();
        JsonNode columns = tableNamed(result.merged(), "orders").path("columns");
        assertThat(columns.get(columns.size() - 1).path("physicalName").asString()).isEqualTo("paid_at");
    }

    @Test
    @DisplayName("DB에 더한 테이블은 위치 없이 만든다 — 에디터가 열 때 배치한다")
    void addedTable() {
        ObjectNode coupons = table("db-t-coupons", "coupons", "coupons_pk",
                col("db-c-coupons-id", "id", "BIGINT").put("nullable", false),
                col("db-c-coupons-code", "code", "VARCHAR").put("length", 30).put("nullable", false));
        ((ArrayNode) coupons.get("uniques")).addObject().put("id", "db-u-1").put("name", "uk_coupons_code")
                .putArray("columnIds").add("db-c-coupons-code");

        DocumentSync.Result result = DocumentSync.sync(doc(List.of(docUsers()), List.of()),
                doc(List.of(dbUsers(), coupons), List.of()), false);

        assertThat(describe(result.items())).containsExactly("table/add coupons.coupons", "column/add coupons.id",
                "column/add coupons.code", "primaryKey/add coupons.coupons_pk", "uniqueKey/add coupons.uk_coupons_code");
        JsonNode created = tableNamed(result.merged(), "coupons");
        String tableId = created.path("id").asString();
        assertThat(tableId).isNotEqualTo("db-t-coupons");
        assertThat(result.merged().path("diagram").path("nodes").has(tableId)).isFalse();
        String codeId = columnNamed(created, "code").path("id").asString();
        assertThat(created.path("uniques").get(0).path("columnIds").get(0).asString()).isEqualTo(codeId);
        assertThat(created.path("primaryKey").path("columnIds").get(0).asString())
                .isEqualTo(columnNamed(created, "id").path("id").asString());
    }

    @Test
    @DisplayName("DB에 더한 FK는 DB의 컬럼 이름 그대로 매핑한다 — 이미 있는 컬럼을 재사용하고 FK 컬럼을 새로 만들지 않는다")
    void addedForeignKeyUsesDbColumns() {
        // 문서에는 user_id 컬럼만 있고 관계가 없다. DB에는 FK가 있고 buyer_id 컬럼·FK도 새로 생겼다
        ObjectNode dbOrders = dbOrders();
        ((ArrayNode) dbOrders.get("columns")).add(col("db-c-orders-buyer", "buyer_id", "BIGINT"));
        ObjectNode buyerFk = rel("db-r-buyer", "fk_orders_buyer", dbUsers(), dbOrders, "db-c-users-id", "db-c-orders-buyer");

        DocumentSync.Result result = DocumentSync.sync(doc(List.of(docUsers(), docOrders()), List.of()),
                doc(List.of(dbUsers(), dbOrders), List.of(dbRel(), buyerFk)), false);

        assertThat(describe(result.items())).containsExactly("column/add orders.buyer_id",
                "relationship/add orders.fk_orders_users", "relationship/add orders.fk_orders_buyer");
        JsonNode orders = tableNamed(result.merged(), "orders");
        assertThat(orders.path("columns")).hasSize(5);
        JsonNode rels = result.merged().path("model").path("relationships");
        assertThat(rels).hasSize(2);
        assertThat(rels.get(0).path("columnMappings").get(0).path("childColumnId").asString()).isEqualTo("t-c-orders-user-id");
        assertThat(rels.get(0).path("columnMappings").get(0).path("parentColumnId").asString()).isEqualTo("t-c-users-id");
        assertThat(rels.get(1).path("columnMappings").get(0).path("childColumnId").asString())
                .isEqualTo(columnNamed(orders, "buyer_id").path("id").asString());
        assertThat(rels.get(1).path("parentTableId").asString()).isEqualTo("t-users");
        // 존 정렬 — PK → FK → 일반
        List<String> order = new ArrayList<>();
        orders.path("columns").forEach(c -> order.add(c.path("physicalName").asString()));
        assertThat(order).containsExactly("id", "user_id", "buyer_id", "status", "memo");
    }

    @Test
    @DisplayName("관계는 (자식, fkName)으로 맞추고 DB의 참조 동작으로 고친다. 매핑이 바뀌면 id를 지키며 매핑을 바꾼다")
    void relationshipPatchAndRemap() {
        ObjectNode dbOrders = dbOrders();
        ((ArrayNode) dbOrders.get("columns")).add(col("db-c-orders-buyer", "buyer_id", "BIGINT"));
        ObjectNode moved = rel("db-r-x", "fk_orders_users", dbUsers(), dbOrders, "db-c-users-id", "db-c-orders-buyer");
        moved.put("onDelete", "CASCADE");

        DocumentSync.Result result = DocumentSync.sync(doc(List.of(docUsers(), docOrders()), List.of(docRel())),
                doc(List.of(dbUsers(), dbOrders), List.of(moved)), false);

        assertThat(result.items()).extracting(DocumentSync.Item::kind, DocumentSync.Item::detail)
                .contains(org.assertj.core.groups.Tuple.tuple("relationship",
                        "columnMappings: id=user_id → id=buyer_id; onDelete: NO_ACTION → CASCADE"));
        JsonNode rel = result.merged().path("model").path("relationships").get(0);
        assertThat(rel.path("id").asString()).isEqualTo("r-orders-users");
        assertThat(rel.path("onDelete").asString()).isEqualTo("CASCADE");
        assertThat(rel.path("columnMappings").get(0).path("childColumnId").asString())
                .isEqualTo(columnNamed(tableNamed(result.merged(), "orders"), "buyer_id").path("id").asString());
    }

    @Test
    @DisplayName("논리명 — DB 코멘트가 없으면 문서 값을 지키고, 있으면 DB 코멘트로 바꾼다")
    void logicalNameRule() {
        ObjectNode dbOrders = dbOrders();
        ((ObjectNode) dbOrders.get("columns").get(2)).put("logicalName", "주문 상태");
        dbOrders.put("logicalName", "주문");

        DocumentSync.Result result = DocumentSync.sync(doc(List.of(docUsers(), docOrders()), List.of(docRel())),
                doc(List.of(dbUsers(), dbOrders), List.of(dbRel())), false);

        JsonNode orders = tableNamed(result.merged(), "orders");
        assertThat(orders.path("logicalName").asString()).isEqualTo("주문");
        assertThat(columnNamed(orders, "status").path("logicalName").asString()).isEqualTo("주문 상태");
        assertThat(columnNamed(orders, "user_id").path("logicalName").asString()).isEqualTo("주문자");
        assertThat(describe(result.items())).containsExactly("table/update orders.orders", "column/update orders.status");
    }

    @Test
    @DisplayName("문서 전용 값 — comment·위치·색·메모·그룹·요구사항은 바뀌지 않는다")
    void documentOnlyValuesPreserved() {
        ObjectNode current = doc(List.of(docUsers(), docOrders()), List.of(docRel()));
        ObjectNode diagram = (ObjectNode) current.get("diagram");
        ((ObjectNode) diagram.get("nodes")).putObject("t-orders").put("x", 111).put("y", 222).put("width", 260).put("color", "sky");
        ((ArrayNode) diagram.get("notes")).addObject().put("id", "n1").put("x", 10).put("y", 20).put("width", 180)
                .put("text", "배포 전 확인").put("title", "").put("color", "yellow").put("linkedTableId", "t-orders");
        ((ArrayNode) diagram.get("areas")).addObject().put("id", "a1").put("name", "주문").put("description", "")
                .put("color", "green").putArray("tableIds").add("t-orders");
        JsonNode before = diagram.deepCopy();
        ObjectNode dbOrders = dbOrders();
        ((ObjectNode) dbOrders.get("columns").get(3)).put("length", 200);

        DocumentSync.Result result = DocumentSync.sync(current, doc(List.of(dbUsers(), dbOrders), List.of(dbRel())), true);

        assertThat(result.items()).hasSize(1);
        assertThat(result.merged().path("diagram")).isEqualTo(before);
        JsonNode orders = tableNamed(result.merged(), "orders");
        assertThat(orders.path("comment").asString()).isEqualTo("팀 공유 메모");
        assertThat(columnNamed(orders, "memo").path("comment").asString()).isEqualTo("내부 메모");
        // 입력 문서는 바꾸지 않는다
        assertThat(columnNamed(tableNamed(current, "orders"), "memo").path("length").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("문서에만 있는 테이블·컬럼·관계·인덱스는 removals로 알리고 includeRemovals=true일 때만 지운다")
    void removalsOnlyWhenIncluded() {
        ObjectNode orders = docOrders();
        ((ArrayNode) orders.get("columns")).add(col("t-c-orders-legacy", "legacy_flag", "BOOLEAN"));
        ((ArrayNode) orders.get("indexes")).addObject().put("id", "i-1").put("name", "idx_orders_status")
                .put("type", "BTREE").putNull("parser").putArray("columns").addObject().put("columnId", "t-c-orders-status").put("order", "ASC");
        ObjectNode archive = table("t-archive", "archive", "archive_pk", col("t-c-archive-id", "id", "BIGINT").put("nullable", false));
        ObjectNode current = doc(List.of(docUsers(), orders, archive), List.of(docRel()));
        ObjectNode diagram = (ObjectNode) current.get("diagram");
        ((ObjectNode) diagram.get("nodes")).putObject("t-archive").put("x", 1).put("y", 2).putNull("width").put("color", "default");
        ((ArrayNode) diagram.get("areas")).addObject().put("id", "a1").put("name", "보관").put("description", "")
                .put("color", "default").putArray("tableIds").add("t-archive").add("t-orders");
        ((ArrayNode) diagram.get("validationExceptions")).addObject().put("id", "v1").put("ruleId", "naming")
                .put("target", "column:t-orders:t-c-orders-legacy").put("reason", "").put("createdBy", "").put("createdAt", "");
        ObjectNode db = doc(List.of(dbUsers(), dbOrders()), List.of());

        DocumentSync.Result kept = DocumentSync.sync(current, db, false);
        assertThat(kept.items()).isEmpty();
        assertThat(describe(kept.removals())).containsExactlyInAnyOrder("relationship/remove orders.fk_orders_users",
                "column/remove orders.legacy_flag", "index/remove orders.idx_orders_status", "table/remove archive.archive");
        assertThat(tableNamed(kept.merged(), "archive")).isNotNull();
        assertThat(columnNamed(tableNamed(kept.merged(), "orders"), "legacy_flag")).isNotNull();
        assertThat(kept.merged().path("model").path("relationships")).hasSize(1);

        DocumentSync.Result removed = DocumentSync.sync(current, db, true);
        assertThat(removed.items()).isEmpty();
        assertThat(removed.fingerprint()).isEqualTo(kept.fingerprint());
        JsonNode merged = removed.merged();
        assertThat(tableNamed(merged, "archive")).isNull();
        assertThat(columnNamed(tableNamed(merged, "orders"), "legacy_flag")).isNull();
        // 관계가 지워져도 DB에 남은 컬럼(user_id)은 문서 값(논리명)과 함께 남는다
        assertThat(columnNamed(tableNamed(merged, "orders"), "user_id").path("logicalName").asString()).isEqualTo("주문자");
        assertThat(tableNamed(merged, "orders").path("indexes")).isEmpty();
        assertThat(merged.path("model").path("relationships")).isEmpty();
        assertThat(merged.path("diagram").path("nodes").has("t-archive")).isFalse();
        assertThat(merged.path("diagram").path("areas").get(0).path("tableIds")).hasSize(1);
        assertThat(merged.path("diagram").path("validationExceptions")).isEmpty();
    }

    @Test
    @DisplayName("UK·CHECK는 DB 우선 전체 교체, 인덱스는 이름으로 맞춰 갱신한다")
    void keysFollowDb() {
        ObjectNode orders = docOrders();
        ((ArrayNode) orders.get("uniques")).addObject().put("id", "u-doc").put("name", "uk_orders_memo")
                .putArray("columnIds").add("t-c-orders-memo");
        ((ArrayNode) orders.get("checks")).addObject().put("id", "c-doc").put("name", "chk_status").put("expression", "status <> ''");
        ((ArrayNode) orders.get("indexes")).addObject().put("id", "i-doc").put("name", "idx_orders_user")
                .put("type", "BTREE").putNull("parser").putArray("columns").addObject().put("columnId", "t-c-orders-user-id").put("order", "ASC");
        ObjectNode dbOrders = dbOrders();
        ((ArrayNode) dbOrders.get("uniques")).addObject().put("id", "db-u").put("name", "uk_orders_status")
                .putArray("columnIds").add("db-c-orders-status");
        ((ArrayNode) dbOrders.get("checks")).addObject().put("id", "db-c").put("name", "chk_status").put("expression", "(`status` <> _utf8mb4'')");
        ObjectNode dbIndex = ((ArrayNode) dbOrders.get("indexes")).addObject().put("id", "db-i").put("name", "idx_orders_user")
                .put("type", "BTREE").putNull("parser");
        dbIndex.putArray("columns").addObject().put("columnId", "db-c-orders-user-id").put("order", "DESC");

        DocumentSync.Result result = DocumentSync.sync(doc(List.of(docUsers(), orders), List.of(docRel())),
                doc(List.of(dbUsers(), dbOrders), List.of(dbRel())), false);

        assertThat(describe(result.items())).containsExactly("uniqueKey/add orders.uk_orders_status",
                "uniqueKey/remove orders.uk_orders_memo", "index/update orders.idx_orders_user", "check/update orders.chk_status");
        JsonNode merged = tableNamed(result.merged(), "orders");
        assertThat(merged.path("uniques")).hasSize(1);
        assertThat(merged.path("checks").get(0).path("id").asString()).isEqualTo("c-doc");
        assertThat(merged.path("indexes").get(0).path("id").asString()).isEqualTo("i-doc");
        assertThat(merged.path("indexes").get(0).path("columns").get(0).path("order").asString()).isEqualTo("DESC");
    }

    @Test
    @DisplayName("멱등성 — 동기화 결과를 같은 DB로 다시 동기화하면 변경이 없다(삭제 포함·제외 모두)")
    void idempotent() {
        ObjectNode orders = docOrders();
        ((ArrayNode) orders.get("columns")).add(col("t-c-orders-legacy", "legacy_flag", "BOOLEAN"));
        ObjectNode dbOrders = dbOrders();
        ((ObjectNode) dbOrders.get("columns").get(2)).put("length", 50).put("logicalName", "상태");
        ((ArrayNode) dbOrders.get("columns")).add(col("db-c-orders-buyer", "buyer_id", "BIGINT"));
        ((ArrayNode) dbOrders.get("uniques")).addObject().put("id", "db-u").put("name", "uk_orders_status")
                .putArray("columnIds").add("db-c-orders-status");
        // 다른 테이블의 키 이름과 겹치는 DB 이름 — 문서 네임스페이스에서 접미가 붙어도 다시 동기화하면 그대로다
        ObjectNode dbIndex = ((ArrayNode) dbOrders.get("indexes")).addObject().put("id", "db-i").put("name", "users_pk")
                .put("type", "BTREE").putNull("parser");
        dbIndex.putArray("columns").addObject().put("columnId", "db-c-orders-buyer").put("order", "ASC");
        ObjectNode coupons = table("db-t-coupons", "coupons", "coupons_pk", col("db-c-coupons-id", "id", "BIGINT").put("nullable", false));
        ObjectNode buyerFk = rel("db-r-buyer", "fk_orders_buyer", dbUsers(), dbOrders, "db-c-users-id", "db-c-orders-buyer");
        ObjectNode db = doc(List.of(dbUsers(), dbOrders, coupons), List.of(dbRel(), buyerFk));

        for (boolean includeRemovals : new boolean[] {false, true}) {
            DocumentSync.Result first = DocumentSync.sync(doc(List.of(docUsers(), orders), List.of(docRel())), db, includeRemovals);
            assertThat(first.items()).isNotEmpty();
            DocumentSync.Result second = DocumentSync.sync(first.merged(), db, includeRemovals);
            assertThat(second.items()).as("includeRemovals=" + includeRemovals).isEmpty();
            assertThat(second.removals()).hasSize(includeRemovals ? 0 : 1);
            assertThat(second.merged()).isEqualTo(first.merged());
            assertThat(tableNamed(first.merged(), "orders").path("indexes").get(0).path("name").asString()).isEqualTo("users_pk_1");
        }
    }

    @Test
    @DisplayName("계획 지문 — DB 본체를 다시 조립해 id가 달라져도 같고, 변경이 달라지면 다르다")
    void fingerprintStable() {
        ObjectNode dbOrders = dbOrders();
        ((ObjectNode) dbOrders.get("columns").get(2)).put("length", 50);
        ObjectNode current = doc(List.of(docUsers(), docOrders()), List.of(docRel()));
        String first = DocumentSync.sync(current, doc(List.of(dbUsers(), dbOrders), List.of(dbRel())), false).fingerprint();

        String json = doc(List.of(dbUsers(), dbOrders), List.of(dbRel())).toString().replace("db-", "db2-");
        String again = DocumentSync.sync(current, JSON.readTree(json), false).fingerprint();
        ((ObjectNode) dbOrders.get("columns").get(2)).put("length", 60);
        String changed = DocumentSync.sync(current, doc(List.of(dbUsers(), dbOrders), List.of(dbRel())), false).fingerprint();

        assertThat(again).isEqualTo(first).hasSize(64);
        assertThat(changed).isNotEqualTo(first);
    }
}
