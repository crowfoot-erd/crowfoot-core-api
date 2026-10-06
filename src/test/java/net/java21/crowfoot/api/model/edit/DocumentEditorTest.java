package net.java21.crowfoot.api.model.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.java21.crowfoot.api.model.edit.EditRequests.AreaItem;
import net.java21.crowfoot.api.model.edit.EditRequests.ColumnItem;
import net.java21.crowfoot.api.model.edit.EditRequests.ColumnMappingItem;
import net.java21.crowfoot.api.model.edit.EditRequests.ColumnRef;
import net.java21.crowfoot.api.model.edit.EditRequests.DomainTypeRef;
import net.java21.crowfoot.api.model.edit.EditRequests.IndexColumnItem;
import net.java21.crowfoot.api.model.edit.EditRequests.IndexItem;
import net.java21.crowfoot.api.model.edit.EditRequests.RelationshipItem;
import net.java21.crowfoot.api.model.edit.EditRequests.RelationshipRef;
import net.java21.crowfoot.api.model.edit.EditRequests.RequirementItem;
import net.java21.crowfoot.api.model.edit.EditRequests.TableItem;
import net.java21.crowfoot.api.model.edit.EditRequests.UniqueItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 문서 편집 엔진 단위 테스트 (08-core/17-model-edit.md Section 2·3·4).
 * 본체를 만드는 규칙이 에디터와 같은지는 docs의 시험 자료로 따로 견준다({@link ModelEditFixtureTest}).
 */
class DocumentEditorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String EMPTY =
            "{\"schemaVersion\":1,\"model\":{\"tables\":[],\"relationships\":[]},\"diagram\":{\"nodes\":{},\"notes\":[],\"viewport\":null}}";

    private ObjectNode root = (ObjectNode) JSON.readTree(EMPTY);
    private final AtomicInteger sequence = new AtomicInteger();
    private Map<String, DomainTypeRef> domainTypes = Map.of();

    private DocumentEditor editor(String databaseType) {
        return new DocumentEditor(root, databaseType, domainTypes, () -> "id-" + sequence.incrementAndGet());
    }

    private static ColumnItem column(String name, String dataType) {
        return new ColumnItem(name, null, null, null, dataType, null, null, null, null, null, null, null);
    }

    private static ColumnItem column(String name, String logicalName, String dataType, Integer length, Boolean nullable, Boolean autoIncrement) {
        return new ColumnItem(name, null, logicalName, null, dataType, length, null, null, nullable, null, autoIncrement, null);
    }

    private static TableItem table(String name, List<ColumnItem> columns, List<String> primaryKey) {
        return new TableItem(name, null, null, null, columns, primaryKey, null, null, null);
    }

    private static RelationshipItem relationship(String parent, String child) {
        return new RelationshipItem(parent, child, null, null, null, null, null, null, null);
    }

    /** orders(id BIGINT PK AI)와 users(id BIGINT PK AI) */
    private void seed(String databaseType) {
        DocumentEditor editor = editor(databaseType);
        editor.applySchema(List.of(
                table("users", List.of(column("id", "회원 ID", "BIGINT", null, null, true), column("email", "VARCHAR")), List.of("id")),
                table("orders", List.of(column("id", "주문 ID", "BIGINT", null, null, true), column("status", "VARCHAR")), List.of("id"))),
                null, null);
        editor.throwIfInvalid();
    }

    private JsonNode tableNode(String name) {
        for (JsonNode table : root.path("model").path("tables")) {
            if (name.equals(table.path("physicalName").asText())) {
                return table;
            }
        }
        throw new AssertionError("no table " + name);
    }

    private List<String> columnNames(String table) {
        List<String> names = new ArrayList<>();
        tableNode(table).path("columns").forEach(column -> names.add(column.path("physicalName").asText()));
        return names;
    }

    private JsonNode columnNode(String table, String column) {
        for (JsonNode node : tableNode(table).path("columns")) {
            if (column.equals(node.path("physicalName").asText())) {
                return node;
            }
        }
        throw new AssertionError("no column " + table + "." + column);
    }

    private JsonNode requirementNode(String code) {
        for (JsonNode node : root.path("diagram").path("requirements")) {
            if (code.equals(node.path("code").asText())) {
                return node;
            }
        }
        throw new AssertionError("no requirement " + code);
    }

    private static List<String> fields(DocumentEditor editor) {
        List<String> fields = new ArrayList<>();
        try {
            editor.throwIfInvalid();
        } catch (EditValidationException e) {
            e.errors().forEach(error -> fields.add(error.field()));
        }
        return fields;
    }

    /* ---------- 스키마 반영 ---------- */

    @Test
    @DisplayName("스펙의 예(Section 4.3) — PostgreSQL 문서에 주문 배송지 테이블과 관계를 더한다")
    void specExamplePostgres() {
        seed("postgresql");
        DocumentEditor requirements = editor("postgresql");
        requirements.applyRequirements(List.of(new RequirementItem(null, "주문 생성", "", "confirmed", null, "주문", null)));
        requirements.throwIfInvalid();

        DocumentEditor editor = editor("postgresql");
        editor.applySchema(
                List.of(new TableItem("order_addresses", null, "주문 배송지", "주문 한 건에 딸린 배송지",
                        List.of(column("id", "주문 배송지 ID", "BIGINT", null, null, true),
                                column("recipient_name", "수령인", "VARCHAR", 100, false, null),
                                column("postal_code", "우편번호", "CHAR", 5, false, null)),
                        List.of("id"), null,
                        List.of(new IndexItem(null, List.of(new IndexColumnItem("postal_code", null)))),
                        List.of("REQ-001"))),
                List.of(new RelationshipItem("orders", "order_addresses", null, null, "EXACTLY_ONE", "ZERO_OR_MORE", "CASCADE", null, null)),
                List.of(new AreaItem("주문", null, "sky", null, List.of("orders", "order_addresses"))));
        editor.throwIfInvalid();

        JsonNode table = tableNode("order_addresses");
        assertThat(table.path("logicalName").asText()).isEqualTo("주문 배송지-----주문 한 건에 딸린 배송지");
        assertThat(table.path("comment").isNull()).isTrue();
        assertThat(table.path("primaryKey").path("name").asText()).isEqualTo("order_addresses_pk");
        // 컬럼 순서 — PK, FK, 나머지 입력 순서
        assertThat(columnNames("order_addresses")).containsExactly("id", "orders_id", "recipient_name", "postal_code");
        JsonNode fk = columnNode("order_addresses", "orders_id");
        assertThat(fk.path("dataType").asText()).isEqualTo("BIGINT");
        assertThat(fk.path("nullable").asBoolean()).isFalse();
        assertThat(fk.path("logicalName").asText()).isEqualTo("주문 ID");
        assertThat(fk.path("autoIncrement").asBoolean()).isFalse();
        assertThat(columnNode("order_addresses", "id").path("nullable").asBoolean()).isFalse();
        JsonNode relationship = root.path("model").path("relationships").get(0);
        assertThat(relationship.path("name").asText()).isEqualTo("fk_order_addresses_orders");
        assertThat(relationship.path("fkName").asText()).isEqualTo("fk_order_addresses_orders");
        assertThat(relationship.path("onDelete").asText()).isEqualTo("CASCADE");
        assertThat(relationship.path("onUpdate").asText()).isEqualTo("NO_ACTION");
        assertThat(relationship.path("type").asText()).isEqualTo("ONE_TO_MANY");
        // 인덱스 — 입력한 것과 FK 인덱스(PostgreSQL 문서라서 만든다)
        List<String> indexes = new ArrayList<>();
        table.path("indexes").forEach(index -> indexes.add(index.path("name").asText()));
        assertThat(indexes).containsExactly("idx_order_addresses_postal_code", "idx_order_addresses_orders_id");
        assertThat(table.path("indexes").get(1).path("columns").get(0).path("order").asText()).isEqualTo("ASC");
        // 요구사항 연결과 그룹
        JsonNode requirement = requirementNode("REQ-001");
        assertThat(DocumentEditor.strings(requirement.path("tableIds"))).containsExactly(table.path("id").asText());
        assertThat(requirement.path("appliedRevision").asInt()).isEqualTo(1);
        assertThat(DocumentOutline.state(requirement)).isEqualTo("APPLIED");
        JsonNode area = root.path("diagram").path("areas").get(0);
        assertThat(area.path("color").asText()).isEqualTo("sky");
        assertThat(area.path("tableIds").size()).isEqualTo(2);
        // 위치는 만들지 않는다
        assertThat(root.path("diagram").path("nodes").isEmpty()).isTrue();
        assertThat(editor.warnings()).isEmpty();
    }

    @Test
    @DisplayName("MySQL 문서에서는 FK 인덱스를 만들지 않는다")
    void noForeignKeyIndexOnMysql() {
        seed("mysql");
        DocumentEditor editor = editor("mysql");
        editor.applySchema(List.of(table("order_items", List.of(column("id", "BIGINT")), List.of("id"))),
                List.of(relationship("orders", "order_items")), null);
        editor.throwIfInvalid();

        assertThat(columnNames("order_items")).containsExactly("id", "orders_id");
        assertThat(tableNode("order_items").path("indexes").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("식별 관계 — FK가 자식의 기본 키에 들어가고 NOT NULL이다. 비식별 1:1은 FK에 유니크 키를 만든다")
    void identifyingAndOneToOne() {
        seed("postgresql");
        DocumentEditor editor = editor("postgresql");
        editor.applySchema(
                List.of(table("order_notes", List.of(column("seq", "INT")), List.of("seq")),
                        table("user_profiles", List.of(column("id", "BIGINT")), List.of("id"))),
                List.of(new RelationshipItem("orders", "order_notes", null, true, null, null, null, null, null),
                        new RelationshipItem("users", "user_profiles", "ONE_TO_ONE", null, "ZERO_OR_ONE", null, null, null, null)),
                null);
        editor.throwIfInvalid();

        // 식별 — PK 블록 끝에 FK가 온다
        assertThat(columnNames("order_notes")).containsExactly("seq", "orders_id");
        assertThat(DocumentEditor.strings(tableNode("order_notes").path("primaryKey").path("columnIds"))).hasSize(2);
        assertThat(columnNode("order_notes", "orders_id").path("nullable").asBoolean()).isFalse();
        assertThat(tableNode("order_notes").path("indexes").isEmpty()).isTrue();
        // 비식별 1:1 — 부모 기수 ZERO_OR_ONE이면 NULL 허용, 유니크 키, 자식 기수 기본값
        assertThat(columnNode("user_profiles", "users_id").path("nullable").asBoolean()).isTrue();
        assertThat(tableNode("user_profiles").path("uniques").get(0).path("name").asText()).isEqualTo("uk_user_profiles_users_id");
        assertThat(tableNode("user_profiles").path("indexes").isEmpty()).isTrue();
        // 자식 기수의 기본값은 에디터와 같다 — 1:1은 EXACTLY_ONE, 1:N은 ONE_OR_MORE
        assertThat(root.path("model").path("relationships").get(1).path("childMultiplicity").asText()).isEqualTo("EXACTLY_ONE");
        assertThat(root.path("model").path("relationships").get(0).path("childMultiplicity").asText()).isEqualTo("ONE_OR_MORE");
    }

    @Test
    @DisplayName("기존 관계를 고친다 — 1:N을 1:1로 바꾸면 FK 인덱스가 유니크 키로 바뀌고, 식별로 바꾸면 FK가 기본 키에 들어간다")
    void updateRelationship() {
        seed("postgresql");
        DocumentEditor create = editor("postgresql");
        create.applySchema(List.of(table("payments", List.of(column("id", "BIGINT")), List.of("id"))),
                List.of(relationship("orders", "payments")), null);
        create.throwIfInvalid();
        assertThat(tableNode("payments").path("indexes").size()).isEqualTo(1);

        DocumentEditor toOne = editor("postgresql");
        toOne.applySchema(null, List.of(new RelationshipItem("orders", "payments", "ONE_TO_ONE", null, null, null, null, null, null)), null);
        toOne.throwIfInvalid();
        assertThat(tableNode("payments").path("indexes").isEmpty()).isTrue();
        assertThat(tableNode("payments").path("uniques").size()).isEqualTo(1);
        assertThat(root.path("model").path("relationships").get(0).path("childMultiplicity").asText()).isEqualTo("EXACTLY_ONE");
        assertThat(root.path("model").path("relationships").size()).isEqualTo(1);

        DocumentEditor identifying = editor("postgresql");
        identifying.applySchema(null, List.of(new RelationshipItem("orders", "payments", null, true, null, null, null, null, null)), null);
        identifying.throwIfInvalid();
        assertThat(tableNode("payments").path("uniques").isEmpty()).isTrue();
        assertThat(DocumentEditor.strings(tableNode("payments").path("primaryKey").path("columnIds"))).hasSize(2);
        assertThat(columnNames("payments")).containsExactly("id", "orders_id");
        assertThat(identifying.changes()).extracting(DocumentEditor.Change::kind).containsExactly("relationship");
    }

    @Test
    @DisplayName("기존 컬럼을 외래 키로 쓴다(columnMappings) — 타입이 부모와 다르면 거부한다")
    void columnMappings() {
        seed("postgresql");
        DocumentEditor editor = editor("postgresql");
        editor.applySchema(
                List.of(table("reviews", List.of(column("id", "BIGINT"), column("body", "TEXT"), column("user_id", "BIGINT"), column("order_no", "INT")), List.of("id"))),
                List.of(new RelationshipItem("users", "reviews", null, null, null, null, null, null, List.of(new ColumnMappingItem("id", "user_id")))),
                null);
        editor.throwIfInvalid();

        // 새 FK 컬럼을 만들지 않고 기존 컬럼을 FK 영역으로 옮긴다
        assertThat(columnNames("reviews")).containsExactly("id", "user_id", "body", "order_no");
        assertThat(columnNode("reviews", "user_id").path("nullable").asBoolean()).isFalse();
        assertThat(tableNode("reviews").path("indexes").get(0).path("name").asText()).isEqualTo("idx_reviews_user_id");

        DocumentEditor mismatch = editor("postgresql");
        mismatch.applySchema(null,
                List.of(new RelationshipItem("orders", "reviews", null, null, null, null, null, null, List.of(new ColumnMappingItem("id", "order_no")))), null);
        assertThat(fields(mismatch)).containsExactly("relationships[0].columnMappings[0].childColumn");
    }

    @Test
    @DisplayName("있는 테이블은 준 것만 고친다 — 입력에 없는 컬럼은 그대로 두고, 새 컬럼은 끝에 붙는다")
    void updateExistingTable() {
        seed("postgresql");
        DocumentEditor editor = editor("postgresql");
        editor.applySchema(List.of(new TableItem("orders", null, "주문", null,
                List.of(new ColumnItem("status", null, "상태", null, null, 20, null, null, false, "'READY'", null, null),
                        new ColumnItem("total_amount", null, "주문 금액", null, "DECIMAL", null, 15, 2, false, "0", null, null)),
                null, List.of(new UniqueItem(null, List.of("status", "total_amount"))), null, null)), null, null);
        editor.throwIfInvalid();

        assertThat(columnNames("orders")).containsExactly("id", "status", "total_amount");
        assertThat(tableNode("orders").path("logicalName").asText()).isEqualTo("주문");
        assertThat(columnNode("orders", "status").path("length").asInt()).isEqualTo(20);
        assertThat(columnNode("orders", "status").path("defaultValue").asText()).isEqualTo("'READY'");
        assertThat(columnNode("orders", "total_amount").path("precision").asInt()).isEqualTo(15);
        assertThat(tableNode("orders").path("uniques").get(0).path("name").asText()).isEqualTo("uk_orders_status_total_amount");
        assertThat(editor.changes()).extracting(change -> change.kind() + ":" + change.action() + ":" + change.name())
                .containsExactly("column:update:status", "column:add:total_amount", "uniqueKey:add:uk_orders_status_total_amount");
        // 근거 요구사항 없이 기존 테이블을 고친 것은 경고가 아니다
        assertThat(editor.warnings()).isEmpty();

        // 같은 요청을 다시 보내면 바뀌는 것이 없다
        String before = root.toString();
        DocumentEditor again = editor("postgresql");
        again.applySchema(List.of(new TableItem("orders", null, "주문", null,
                List.of(new ColumnItem("status", null, "상태", null, null, 20, null, null, false, "'READY'", null, null)),
                null, List.of(new UniqueItem(null, List.of("status", "total_amount"))), null, null)), null, null);
        again.throwIfInvalid();
        assertThat(root.toString()).isEqualTo(before);
        assertThat(again.changes()).isEmpty();
    }

    @Test
    @DisplayName("입력 검증 — 틀린 항목마다 사유를 모은다")
    void validation() {
        seed("postgresql");
        DocumentEditor editor = editor("postgresql");
        editor.applySchema(
                List.of(table("Order-Items", List.of(), null),
                        table("coupons", List.of(column("id", "VARCHAR2"), column("code", "VARCHAR"), column("seq", null, "INT", null, null, true)), null),
                        new TableItem("orders", null, null, null, List.of(new ColumnItem("id", null, null, null, "INT", 10, null, null, null, null, null, null)), null, null, null, List.of("REQ-404")),
                        table("logs", List.of(column("message", "TEXT")), null)),
                List.of(relationship("logs", "orders"),
                        new RelationshipItem("users", "orders", "MANY_TO_MANY", null, null, null, null, null, null),
                        new RelationshipItem("users", "orders", null, null, null, "ZERO_OR_ONE", null, null, null)),
                List.of(new AreaItem("주문", null, "purple", null, null)));

        assertThat(fields(editor)).containsExactly(
                "tables[0].physicalName",
                "tables[1].columns[0].dataType",
                "tables[1].columns",
                "tables[2].columns[0].length",
                "tables[2].requirementCodes[0]",
                "relationships[0].parent",
                "relationships[1].type",
                "relationships[2].childMultiplicity",
                "areas[0].color");
    }

    @Test
    @DisplayName("외래 키 컬럼의 타입은 직접 고칠 수 없다 — 논리명은 고칠 수 있다")
    void foreignKeyColumnIsOwnedByRelationship() {
        seed("postgresql");
        DocumentEditor create = editor("postgresql");
        create.applySchema(List.of(table("payments", List.of(column("id", "BIGINT")), List.of("id"))), List.of(relationship("orders", "payments")), null);
        create.throwIfInvalid();

        DocumentEditor retype = editor("postgresql");
        retype.applySchema(List.of(table("payments", List.of(column("orders_id", "INT")), null)), null, null);
        assertThat(fields(retype)).containsExactly("tables[0].columns[0]");

        DocumentEditor rename = editor("postgresql");
        rename.applySchema(List.of(table("payments", List.of(new ColumnItem("orders_id", null, "주문", null, null, null, null, null, null, null, null, null)), null)), null, null);
        rename.throwIfInvalid();
        assertThat(columnNode("payments", "orders_id").path("logicalName").asText()).isEqualTo("주문");
    }

    @Test
    @DisplayName("도메인 타입 — 값을 채우고 연결을 남긴다. 같이 준 값이 다르면 '다르게 씀'으로 기록한다")
    void domainType() {
        domainTypes = Map.of("이메일", new DomainTypeRef("11", "이메일", 3, "VARCHAR", 191, null, null, false, null));
        DocumentEditor editor = editor("postgresql");
        editor.applySchema(List.of(table("users", List.of(
                new ColumnItem("email", null, null, null, null, null, null, null, null, null, null, "이메일"),
                new ColumnItem("backup_email", null, null, null, null, 320, null, null, true, null, null, "이메일"),
                new ColumnItem("nickname", null, null, null, "VARCHAR", 50, null, null, null, null, null, "없는 타입")), null)), null, null);
        editor.throwIfInvalid();

        JsonNode email = columnNode("users", "email");
        assertThat(email.path("dataType").asText()).isEqualTo("VARCHAR");
        assertThat(email.path("length").asInt()).isEqualTo(191);
        assertThat(email.path("nullable").asBoolean()).isFalse();
        assertThat(email.path("domain").path("id").asText()).isEqualTo("11");
        assertThat(email.path("domain").path("version").asInt()).isEqualTo(3);
        assertThat(email.path("domain").path("overrides").isEmpty()).isTrue();
        assertThat(DocumentEditor.strings(columnNode("users", "backup_email").path("domain").path("overrides"))).containsExactly("length", "nullable");
        assertThat(columnNode("users", "nickname").has("domain")).isFalse();
        assertThat(editor.warnings()).extracting(DocumentEditor.Warning::code).containsExactly("DOMAIN_TYPE_NOT_FOUND", "UNTRACED_TABLE");
    }

    /* ---------- 요구사항 ---------- */

    @Test
    @DisplayName("요구사항 — 코드를 붙이고, 도메인 그룹을 만들고, 내용이 바뀌면 개정 번호가 오른다")
    void requirements() {
        seed("postgresql");
        DocumentEditor create = editor("postgresql");
        create.applyRequirements(List.of(
                new RequirementItem("REQ-001", "주문 생성", "회원은 상품을 주문한다", "confirmed", null, "주문", null),
                new RequirementItem("REQ-010", "회원 가입", null, null, null, "회원", List.of("users")),
                new RequirementItem(null, "생성·수정 일시", "모든 테이블에 둔다", "confirmed", "document", null, null)));
        create.throwIfInvalid();

        assertThat(requirementNode("REQ-001").path("title").asText()).isEqualTo("주문 생성");
        // 자동 번호는 문서와 요청에 있는 가장 큰 번호 다음이다
        assertThat(requirementNode("REQ-011").path("scope").asText()).isEqualTo("document");
        assertThat(root.path("diagram").path("areas").size()).isEqualTo(2);
        assertThat(DocumentOutline.state(requirementNode("REQ-001"))).isEqualTo("PENDING");
        assertThat(DocumentOutline.state(requirementNode("REQ-010"))).isEqualTo("DRAFT");
        assertThat(requirementNode("REQ-010").path("appliedRevision").asInt()).isEqualTo(1);
        assertThat(DocumentOutline.state(requirementNode("REQ-011"))).isEqualTo("APPLIED");

        // 테이블을 연결하면 반영됨, 내용을 고치면 다시 반영 대기
        DocumentEditor link = editor("postgresql");
        link.applySchema(List.of(new TableItem("orders", null, null, null, null, null, null, null, List.of("REQ-001"))), null, null);
        link.throwIfInvalid();
        assertThat(DocumentOutline.state(requirementNode("REQ-001"))).isEqualTo("APPLIED");
        assertThat(link.changes()).extracting(DocumentEditor.Change::kind).containsExactly("requirement");

        DocumentEditor revise = editor("postgresql");
        revise.applyRequirements(List.of(new RequirementItem("REQ-001", null, "회원은 상품을 주문한다. 배송지는 여러 개다", null, null, null, null)));
        revise.throwIfInvalid();
        assertThat(requirementNode("REQ-001").path("revision").asInt()).isEqualTo(2);
        assertThat(DocumentOutline.state(requirementNode("REQ-001"))).isEqualTo("PENDING");
        // 주지 않은 필드는 그대로다
        assertThat(requirementNode("REQ-001").path("title").asText()).isEqualTo("주문 생성");
        assertThat(requirementNode("REQ-001").path("status").asText()).isEqualTo("confirmed");

        // 제외했는데 테이블이 남아 있으면 정리 필요
        DocumentEditor drop = editor("postgresql");
        drop.applyRequirements(List.of(new RequirementItem("REQ-001", null, null, "dropped", null, "", null)));
        drop.throwIfInvalid();
        assertThat(DocumentOutline.state(requirementNode("REQ-001"))).isEqualTo("LEFTOVER");
        assertThat(requirementNode("REQ-001").path("areaId").isNull()).isTrue();

        Map<String, Object> summary = DocumentOutline.requirementSummary(root);
        assertThat(summary.get("pending")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("요구사항 검증 — 코드 형식, 중복, 공통 요구사항의 연결, 없는 테이블, 범위 변경")
    void requirementValidation() {
        seed("postgresql");
        DocumentEditor seedRequirement = editor("postgresql");
        seedRequirement.applyRequirements(List.of(new RequirementItem("REQ-001", "주문 생성", null, null, null, null, null)));
        seedRequirement.throwIfInvalid();

        DocumentEditor editor = editor("postgresql");
        editor.applyRequirements(List.of(
                new RequirementItem("ORD-1", "x", null, null, null, null, null),
                new RequirementItem("REQ-002", "a", null, null, null, null, null),
                new RequirementItem("REQ-002", "b", null, null, null, null, null),
                new RequirementItem(null, "공통", null, null, "document", "주문", null),
                new RequirementItem(null, "연결", null, null, null, null, List.of("nope")),
                new RequirementItem("REQ-001", null, null, null, "document", null, null),
                new RequirementItem(null, " ", null, null, null, null, null),
                new RequirementItem(null, "상태", null, "done", null, null, null)));

        assertThat(fields(editor)).containsExactly("items[0].code", "items[2].code", "items[3].domain", "items[4].tables[0]",
                "items[5].scope", "items[6].title", "items[7].status");
    }

    /* ---------- 삭제 ---------- */

    @Test
    @DisplayName("삭제 — 테이블을 지우면 관계, 상대 테이블의 FK 컬럼과 그 키, 그룹과 요구사항의 참조가 함께 정리된다")
    void removeCascade() {
        seed("postgresql");
        DocumentEditor create = editor("postgresql");
        create.applyRequirements(List.of(new RequirementItem(null, "주문 생성", null, "confirmed", null, "주문", List.of("orders"))));
        create.applySchema(List.of(table("payments", List.of(column("id", "BIGINT"), column("memo", "TEXT")), List.of("id"))),
                List.of(relationship("orders", "payments"), relationship("users", "orders")),
                List.of(new AreaItem("주문", null, null, null, List.of("orders", "payments"))));
        create.throwIfInvalid();
        assertThat(columnNames("payments")).containsExactly("id", "orders_id", "memo");
        ((ObjectNode) root.path("diagram").path("nodes")).putObject(tableNode("orders").path("id").asText()).put("x", 10).put("y", 20);

        DocumentEditor remove = editor("postgresql");
        remove.remove(List.of("orders"), null, null, null);
        remove.throwIfInvalid();

        assertThat(root.path("model").path("tables").size()).isEqualTo(2);
        assertThat(root.path("model").path("relationships").isEmpty()).isTrue();
        // 자식 쪽 FK 컬럼과 FK 인덱스가 사라진다
        assertThat(columnNames("payments")).containsExactly("id", "memo");
        assertThat(tableNode("payments").path("indexes").isEmpty()).isTrue();
        assertThat(root.path("diagram").path("nodes").isEmpty()).isTrue();
        assertThat(root.path("diagram").path("areas").get(0).path("tableIds").size()).isEqualTo(1);
        assertThat(requirementNode("REQ-001").path("tableIds").isEmpty()).isTrue();
        assertThat(DocumentOutline.state(requirementNode("REQ-001"))).isEqualTo("UNLINKED");
        assertThat(remove.warnings()).extracting(DocumentEditor.Warning::code).containsExactly("UNLINKED_REQUIREMENT");
        assertThat(remove.changes()).extracting(change -> change.kind() + ":" + change.action()).containsExactly("table:remove");
    }

    @Test
    @DisplayName("삭제 — 관계를 지우면 FK 컬럼이 지워지고, 부모의 기본 키 컬럼을 지우면 관계부터 지워진다. 없는 대상이 있으면 아무것도 지우지 않는다")
    void removeRelationshipAndColumn() {
        seed("postgresql");
        DocumentEditor create = editor("postgresql");
        create.applySchema(List.of(table("payments", List.of(column("id", "BIGINT")), List.of("id"))),
                List.of(relationship("orders", "payments"), relationship("users", "orders")), null);
        create.throwIfInvalid();

        String before = root.toString();
        DocumentEditor missing = editor("postgresql");
        missing.remove(List.of("payments", "nope"), List.of(new ColumnRef("orders", "nope")), null, List.of("REQ-999"));
        assertThat(fields(missing)).containsExactly("columns[0]", "tables[1]", "requirements[0]");
        assertThat(root.toString()).isEqualTo(before);

        DocumentEditor relationship = editor("postgresql");
        relationship.remove(null, null, List.of(new RelationshipRef("orders", "payments")), null);
        relationship.throwIfInvalid();
        assertThat(columnNames("payments")).containsExactly("id");

        // users.id는 users → orders 관계의 부모 컬럼이다 — 관계와 orders.users_id가 함께 사라진다
        DocumentEditor column = editor("postgresql");
        column.remove(null, List.of(new ColumnRef("users", "id")), null, null);
        column.throwIfInvalid();
        assertThat(columnNames("users")).containsExactly("email");
        assertThat(tableNode("users").path("primaryKey").isNull()).isTrue();
        assertThat(columnNames("orders")).containsExactly("id", "status");
        assertThat(root.path("model").path("relationships").isEmpty()).isTrue();
    }

    /* ---------- 그 밖에 ---------- */

    @Test
    @DisplayName("이 엔진이 모르는 키는 그대로 남는다 — 메모, 뷰포트, 테이블의 색")
    void preservesUnknownKeys() {
        root = (ObjectNode) JSON.readTree("{\"schemaVersion\":1,\"future\":{\"a\":1},\"model\":{\"tables\":[{\"id\":\"t1\",\"logicalName\":\"\",\"physicalName\":\"users\",\"comment\":\"문서 메모\","
                + "\"columns\":[{\"id\":\"c1\",\"logicalName\":\"\",\"physicalName\":\"id\",\"dataType\":\"BIGINT\",\"length\":null,\"precision\":null,\"scale\":null,\"nullable\":false,\"defaultValue\":null,\"autoIncrement\":true,\"comment\":null,\"extra\":true}],"
                + "\"primaryKey\":{\"name\":\"users_pk\",\"columnIds\":[\"c1\"]}}],\"relationships\":[]},"
                + "\"diagram\":{\"nodes\":{\"t1\":{\"x\":120,\"y\":80,\"width\":null,\"color\":\"blue\"}},\"notes\":[{\"id\":\"n1\",\"x\":0,\"y\":0,\"width\":300,\"text\":\"메모\",\"linkedTableId\":\"t1\"}],\"viewport\":{\"x\":1,\"y\":2,\"zoom\":0.8}}}");
        assertThat(DocumentEditor.readable(root)).isTrue();

        DocumentEditor editor = editor("postgresql");
        editor.applySchema(List.of(table("users", List.of(column("email", "VARCHAR")), null)), null, null);
        editor.throwIfInvalid();

        assertThat(root.path("future").path("a").asInt()).isEqualTo(1);
        assertThat(tableNode("users").path("comment").asText()).isEqualTo("문서 메모");
        assertThat(columnNode("users", "id").path("extra").asBoolean()).isTrue();
        assertThat(root.path("diagram").path("nodes").path("t1").path("color").asText()).isEqualTo("blue");
        assertThat(root.path("diagram").path("notes").get(0).path("text").asText()).isEqualTo("메모");
        assertThat(root.path("diagram").path("viewport").path("zoom").asDouble()).isEqualTo(0.8);
        // uniques·indexes가 없던 옛 테이블은 빈 배열로 채운다
        assertThat(tableNode("users").path("uniques").isArray()).isTrue();
    }

    @Test
    @DisplayName("읽을 수 없는 본체 — 구조가 어긋나면 고치지 않는다")
    void unreadable() {
        assertThat(DocumentEditor.readable(JSON.readTree("{\"tables\":[]}"))).isFalse();
        assertThat(DocumentEditor.readable(JSON.readTree("{\"model\":{\"tables\":{},\"relationships\":[]},\"diagram\":{}}"))).isFalse();
        assertThat(DocumentEditor.readable(JSON.readTree("{\"model\":{\"tables\":[{\"id\":\"t\"}],\"relationships\":[]},\"diagram\":{}}"))).isFalse();
        assertThat(DocumentEditor.readable(JSON.readTree(EMPTY))).isTrue();
    }

    @Test
    @DisplayName("개요 — 이름 기준으로 돌려주고 UUID와 좌표는 넣지 않는다")
    void outline() {
        seed("postgresql");
        DocumentEditor editor = editor("postgresql");
        editor.applyRequirements(List.of(new RequirementItem(null, "주문 생성", "내용", "confirmed", null, "주문", List.of("orders"))));
        editor.applySchema(List.of(new TableItem("orders", null, "주문", "주문 한 건", null, null, null, null, null)),
                List.of(relationship("users", "orders")), List.of(new AreaItem("주문", null, null, null, List.of("orders"))));
        editor.throwIfInvalid();

        Map<String, Object> outline = DocumentOutline.build(root);
        String text = JSON.writeValueAsString(outline);

        assertThat(text).doesNotContain("id-").doesNotContain("\"x\"");
        JsonNode node = JSON.readTree(text);
        JsonNode orders = node.path("tables").get(1);
        assertThat(orders.path("logicalName").asText()).isEqualTo("주문");
        assertThat(orders.path("description").asText()).isEqualTo("주문 한 건");
        assertThat(orders.path("primaryKey").get(0).asText()).isEqualTo("id");
        assertThat(orders.path("areas").get(0).asText()).isEqualTo("주문");
        assertThat(orders.path("requirementCodes").get(0).asText()).isEqualTo("REQ-001");
        JsonNode fk = orders.path("columns").get(1);
        assertThat(fk.path("physicalName").asText()).isEqualTo("users_id");
        assertThat(fk.path("foreignKey").asBoolean()).isTrue();
        assertThat(node.path("relationships").get(0).path("parent").asText()).isEqualTo("users");
        assertThat(node.path("relationships").get(0).path("columnMappings").get(0).path("childColumn").asText()).isEqualTo("users_id");
        assertThat(node.path("requirements").get(0).path("domain").asText()).isEqualTo("주문");
        assertThat(node.path("requirements").get(0).path("state").asText()).isEqualTo("APPLIED");
        assertThat(node.path("untracedTables").get(0).asText()).isEqualTo("users");
        assertThat(node.path("requirementSummary").path("counts").path("APPLIED").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("요구사항 500개 상한")
    void requirementLimit() {
        List<RequirementItem> batch = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            batch.add(new RequirementItem(null, "요구사항", null, null, null, null, null));
        }
        for (int round = 0; round < 2; round++) {
            DocumentEditor editor = editor("postgresql");
            editor.applyRequirements(batch);
            editor.throwIfInvalid();
        }
        batch.add(new RequirementItem(null, "하나 더", null, null, null, null, null));
        assertThatThrownBy(() -> editor("postgresql").applyRequirements(batch.subList(0, 101)))
                .isInstanceOf(DocumentEditor.RequirementLimitException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("이름 변경 — 논리명이 예전 물리명 그대로였으면 새 물리명을 따라간다(가져오기가 채운 값), 직접 쓴 논리명은 그대로")
    void logicalNameFollowsRename() {
        seed("mysql");
        DocumentEditor first = editor("mysql");
        first.applySchema(List.of(new TableItem("users", null, null, null,
                List.of(new ColumnItem("email", null, "email", null, null, 100, null, null, null, null, null, null)), null, null, null, null)), null, null);
        first.throwIfInvalid();

        DocumentEditor editor = editor("mysql");
        editor.applySchema(List.of(new TableItem("users", null, null, null, List.of(
                new ColumnItem("email", "contact_email", null, null, null, null, null, null, null, null, null, null),
                new ColumnItem("id", "user_id", null, null, null, null, null, null, null, null, null, null)), null, null, null, null)), null, null);
        editor.throwIfInvalid();

        List<String> names = new java.util.ArrayList<>();
        tableNode("users").path("columns").forEach(c -> names.add(c.path("physicalName").asText() + "=" + c.path("logicalName").asText()));
        assertThat(names).contains("contact_email=contact_email", "user_id=회원 ID");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("같은 컬럼의 FULLTEXT 인덱스가 있어도 BTREE 인덱스를 새로 더한다 — 종류를 덮어쓰지 않는다")
    void sameColumnsDifferentIndexType() {
        seed("mysql");
        DocumentEditor first = editor("mysql");
        first.applySchema(List.of(new TableItem("users", null, null, null, null, null, null,
                List.of(new IndexItem("ft_users_email", List.of(new IndexColumnItem("email", null)), "FULLTEXT", "ngram")), null)), null, null);
        first.throwIfInvalid();
        DocumentEditor second = editor("mysql");
        second.applySchema(List.of(new TableItem("users", null, null, null, null, null, null,
                List.of(new IndexItem("idx_users_email", List.of(new IndexColumnItem("email", null)))), null)), null, null);
        second.throwIfInvalid();

        List<String> indexes = new java.util.ArrayList<>();
        tableNode("users").path("indexes").forEach(i -> indexes.add(i.path("name").asText() + ":" + i.path("type").asText()));
        assertThat(indexes).containsExactly("ft_users_email:FULLTEXT", "idx_users_email:BTREE");
    }
}
