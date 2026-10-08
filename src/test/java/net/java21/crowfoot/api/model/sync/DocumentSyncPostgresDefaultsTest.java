package net.java21.crowfoot.api.model.sync;

import net.java21.crowfoot.api.connection.introspect.PostgresIntrospector;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.model.ddl.DdlContent;
import net.java21.crowfoot.api.model.ddl.DdlGenerator;
import net.java21.crowfoot.api.model.ddl.Dialects;
import net.java21.crowfoot.api.model.ddl.ErdContentParser;
import net.java21.crowfoot.api.model.ddl.SchemaDiffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 기본값 왕복 실측 (커뮤니티 신고 50) — MCP 문서 규칙대로 적은 기본값(문자열은 따옴표 없이, 함수 호출 식)으로
 * 배포 DDL을 만들어 실제 PostgreSQL 16에 실행하고, 다시 읽은 DB와 문서를 비교한다. 배포 직후이므로 동기화 계획(plan_sync)과
 * 변경 계획(plan_migration의 SchemaDiffer) 모두 차이가 없어야 한다. docker 데몬이 없으면 스킵한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentSyncPostgresDefaultsTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ObjectNode col(String id, String name, String type, String defaultValue) {
        ObjectNode column = JSON.createObjectNode();
        column.put("id", id);
        column.put("logicalName", name);
        column.put("physicalName", name);
        column.put("dataType", type);
        column.putNull("length");
        column.putNull("precision");
        column.putNull("scale");
        column.put("nullable", !"id".equals(name));
        if (defaultValue == null) {
            column.putNull("defaultValue");
        } else {
            column.put("defaultValue", defaultValue);
            column.put("nullable", false);
        }
        column.put("autoIncrement", false);
        column.putNull("comment");
        column.putNull("generated");
        column.putNull("onUpdate");
        return column;
    }

    private static ObjectNode document() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        ObjectNode table = JSON.createObjectNode();
        table.put("id", "t-users");
        table.put("logicalName", "users");
        table.put("physicalName", "users");
        table.putNull("comment");
        ArrayNode columns = table.putArray("columns");
        columns.add(col("c-id", "id", "BIGINT", null));
        columns.add(col("c-role", "role", "TEXT", "member"));
        columns.add(col("c-widgets", "hidden_widgets", "TEXT", "[]"));
        columns.add(col("c-quote", "motto", "TEXT", "it's"));
        columns.add(col("c-empty", "nickname", "TEXT", "''"));
        columns.add(col("c-created", "created_at", "TEXT", "to_char(LOCALTIMESTAMP, 'YYYY-MM-DD HH24:MI:SS')"));
        columns.add(col("c-score", "score", "DECIMAL", "0"));
        columns.add(col("c-active", "active", "BOOLEAN", "true"));
        columns.add(col("c-stamp", "stamped_at", "TIMESTAMP", "CURRENT_TIMESTAMP"));
        table.putObject("primaryKey").put("name", "users_pk").putArray("columnIds").add("c-id");
        table.putArray("uniques");
        table.putArray("indexes");
        table.putArray("checks");
        ObjectNode model = root.putObject("model");
        model.putArray("tables").add(table);
        model.putArray("relationships");
        ObjectNode diagram = root.putObject("diagram");
        diagram.putObject("nodes");
        diagram.putArray("notes");
        diagram.putArray("areas");
        diagram.putArray("requirements");
        diagram.putArray("validationExceptions");
        diagram.putNull("viewport");
        return root;
    }

    @Test
    @DisplayName("배포 직후 다시 읽은 기본값이 문서와 같다 — to_char 닫는 괄호 유지, 문자열 따옴표 없음, 동기화·변경 계획 0")
    void defaultsRoundTrip() throws Exception {
        ObjectNode document = document();
        DdlContent content = ErdContentParser.parse(document);
        DdlGenerator.Result ddl = DdlGenerator.generate(content, Dialects.byId("postgres"), "PostgreSQL", "defaults");
        try (Connection db = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = db.createStatement()) {
            for (String sql : ddl.statements()) {
                statement.execute(sql);
            }
        }

        JsonNode dbContent;
        try (Connection db = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            dbContent = JSON.readTree(new ReverseContentAssembler()
                    .assemble(new PostgresIntrospector().introspect(db, "public"), new PostgresIntrospector(), "postgresql", false)
                    .content());
        }
        JsonNode dbColumns = dbContent.path("model").path("tables").get(0).path("columns");
        assertThat(defaultOf(dbColumns, "created_at")).isEqualTo("to_char(LOCALTIMESTAMP, 'YYYY-MM-DD HH24:MI:SS')");
        assertThat(defaultOf(dbColumns, "role")).isEqualTo("member");
        assertThat(defaultOf(dbColumns, "hidden_widgets")).isEqualTo("[]");
        assertThat(defaultOf(dbColumns, "motto")).isEqualTo("it's");
        assertThat(defaultOf(dbColumns, "nickname")).isEqualTo("''");

        DocumentSync.Result sync = DocumentSync.sync(document, dbContent, false);
        assertThat(sync.items()).extracting(DocumentSync.Item::name, DocumentSync.Item::detail).isEmpty();

        List<SchemaDiffer.Change> changes = SchemaDiffer.diff(ErdContentParser.parse(dbContent), content, false).changes().stream()
                .filter(change -> !(change instanceof SchemaDiffer.CommentRefresh)).toList();
        assertThat(changes).isEmpty();
    }

    private static String defaultOf(JsonNode columns, String name) {
        for (JsonNode column : columns) {
            if (name.equals(column.path("physicalName").asString())) {
                return column.path("defaultValue").asString(null);
            }
        }
        throw new AssertionError("no column " + name);
    }
}
