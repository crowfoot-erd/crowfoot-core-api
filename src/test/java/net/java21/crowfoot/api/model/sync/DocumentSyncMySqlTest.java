package net.java21.crowfoot.api.model.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import net.java21.crowfoot.api.connection.introspect.MySqlIntrospector;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.model.ddl.DdlContent;
import net.java21.crowfoot.api.model.ddl.ErdContentParser;
import net.java21.crowfoot.api.model.ddl.SchemaDiffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DB → 문서 동기화 실측 (08-core/02-model.md §1.16) — 실제 MySQL에서 리버스로 만든 문서를 두고 DB를 ALTER로 바꾼 뒤
 * 계획과 병합을 돌린다. 병합한 문서는 DB와 같아야 한다(SchemaDiffer 차이 없음 — 코멘트 갱신만 예외).
 * docker 데몬이 없으면 스킵한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentSyncMySqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withUsername("root")
            .withPassword("root-test-pass");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReverseContentAssembler assembler = new ReverseContentAssembler();
    private final MySqlIntrospector introspector = new MySqlIntrospector();

    @Test
    @DisplayName("MySQL — ALTER로 바꾼 DB를 동기화하면 문서가 DB와 같아지고 문서 전용 값은 남는다")
    void syncAfterAlter() throws Exception {
        try (Connection admin = mysql("")) {
            execute(admin, "CREATE DATABASE sync_db");
        }
        try (Connection db = mysql("sync_db")) {
            execute(db, "CREATE TABLE users (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, email VARCHAR(100) NOT NULL)");
            execute(db, "CREATE TABLE orders (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,"
                    + " status VARCHAR(20) NOT NULL DEFAULT 'NEW', memo VARCHAR(100) NULL,"
                    + " CONSTRAINT fk_orders_users FOREIGN KEY (user_id) REFERENCES users (id))");
            execute(db, "CREATE TABLE legacy_log (id BIGINT NOT NULL PRIMARY KEY)");
        }

        // 문서 — 리버스로 만든 새 문서(FK 인덱스·위치 포함)에 문서 전용 값을 더한다
        ObjectNode document = (ObjectNode) mapper.readTree(assembler.assemble(introspect(), introspector, "mysql").content());
        ObjectNode orders = table(document, "orders");
        orders.put("comment", "팀 공유 메모");
        column(orders, "user_id").put("logicalName", "주문자");
        String ordersId = orders.get("id").asString();
        ((ArrayNode) document.get("diagram").get("notes")).addObject().put("id", "n1").put("x", 10).put("y", 20)
                .put("width", 180).put("text", "배포 전 확인").put("title", "").put("color", "yellow").put("linkedTableId", ordersId);
        ((ObjectNode) document.get("diagram")).putArray("areas").addObject().put("id", "a1").put("name", "주문")
                .put("description", "").put("color", "green").putArray("tableIds").add(ordersId);
        JsonNode ordersNode = document.get("diagram").get("nodes").get(ordersId).deepCopy();

        try (Connection db = mysql("sync_db")) {
            execute(db, "ALTER TABLE orders MODIFY COLUMN status VARCHAR(50) NOT NULL DEFAULT 'NEW'");
            execute(db, "ALTER TABLE orders ADD COLUMN paid_at DATETIME(6) NULL");
            execute(db, "ALTER TABLE orders MODIFY COLUMN memo VARCHAR(100) NULL COMMENT '메모'");
            execute(db, "CREATE INDEX idx_orders_paid ON orders (paid_at DESC)");
            execute(db, "CREATE TABLE coupons (id BIGINT NOT NULL PRIMARY KEY, code VARCHAR(30) NOT NULL,"
                    + " rate INT NOT NULL, CONSTRAINT uk_coupons_code UNIQUE (code), CONSTRAINT chk_coupons_rate CHECK (rate >= 0))"
                    + " COMMENT '쿠폰'");
            execute(db, "ALTER TABLE orders ADD COLUMN coupon_id BIGINT NULL,"
                    + " ADD CONSTRAINT fk_orders_coupons FOREIGN KEY (coupon_id) REFERENCES coupons (id) ON DELETE SET NULL");
            execute(db, "DROP TABLE legacy_log");
        }

        String dbJson = assembler.assemble(introspect(), introspector, "mysql", false).content();
        JsonNode dbContent = mapper.readTree(dbJson);
        DocumentSync.Result plan = DocumentSync.sync(document, dbContent, false);

        assertThat(plan.items()).extracting(item -> item.kind() + "/" + item.action() + " " + item.table() + "." + item.name())
                .contains("column/update orders.status", "column/add orders.paid_at", "column/update orders.memo",
                        "index/add orders.idx_orders_paid", "table/add coupons.coupons", "uniqueKey/add coupons.uk_coupons_code",
                        "check/add coupons.chk_coupons_rate", "column/add orders.coupon_id", "relationship/add orders.fk_orders_coupons");
        // 리버스 문서에 들어간 FK 인덱스는 MySQL에서 만들지 않는다 — 문서에만 있는 것은 legacy_log뿐이다
        assertThat(plan.removals()).extracting(item -> item.kind() + "/" + item.action() + " " + item.name())
                .containsExactly("table/remove legacy_log");

        DocumentSync.Result applied = DocumentSync.sync(document, dbContent, true);
        assertThat(applied.fingerprint()).isEqualTo(plan.fingerprint());
        ObjectNode merged = applied.merged();

        // 병합 문서와 DB 사이에 구조 차이가 없다(논리명 코멘트 갱신 제외)
        DdlContent dbDdl = ErdContentParser.parse(dbContent);
        DdlContent mergedDdl = ErdContentParser.parse(merged);
        List<SchemaDiffer.Change> changes = SchemaDiffer.diff(dbDdl, mergedDdl, false).changes().stream()
                .filter(change -> !(change instanceof SchemaDiffer.CommentRefresh)).toList();
        assertThat(changes).isEmpty();

        // 다시 동기화하면 변경이 없다
        DocumentSync.Result again = DocumentSync.sync(merged, mapper.readTree(
                assembler.assemble(introspect(), introspector, "mysql", false).content()), true);
        assertThat(again.items()).isEmpty();
        assertThat(again.removals()).isEmpty();

        // 문서 전용 값은 남는다
        ObjectNode mergedOrders = table(merged, "orders");
        assertThat(mergedOrders.get("id").asString()).isEqualTo(ordersId);
        assertThat(mergedOrders.get("comment").asString()).isEqualTo("팀 공유 메모");
        assertThat(column(mergedOrders, "user_id").get("logicalName").asString()).isEqualTo("주문자");
        assertThat(column(mergedOrders, "memo").get("logicalName").asString()).isEqualTo("메모");
        assertThat(merged.get("diagram").get("nodes").get(ordersId)).isEqualTo(ordersNode);
        assertThat(merged.get("diagram").get("notes").get(0).get("linkedTableId").asString()).isEqualTo(ordersId);
        assertThat(merged.get("diagram").get("areas").get(0).get("tableIds").get(0).asString()).isEqualTo(ordersId);
        assertThat(table(merged, "coupons").get("logicalName").asString()).isEqualTo("쿠폰");
        assertThat(merged.get("diagram").get("nodes").has(table(merged, "coupons").get("id").asString())).isFalse();
    }

    private ObjectNode table(JsonNode root, String name) {
        for (JsonNode table : root.get("model").get("tables")) {
            if (name.equals(table.get("physicalName").asString())) {
                return (ObjectNode) table;
            }
        }
        throw new AssertionError("테이블 없음: " + name);
    }

    private ObjectNode column(JsonNode table, String name) {
        for (JsonNode column : table.get("columns")) {
            if (name.equals(column.get("physicalName").asString())) {
                return (ObjectNode) column;
            }
        }
        throw new AssertionError("컬럼 없음: " + name);
    }

    private net.java21.crowfoot.api.connection.introspect.IntrospectedSchema introspect() throws SQLException {
        try (Connection db = mysql("sync_db")) {
            return introspector.introspect(db, null);
        }
    }

    private static Connection mysql(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/" + database, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
