package net.java21.crowfoot.api.connection.reverse;

import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.PostgresIntrospector;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리버스 content 조립 테스트 (05-editor/04-dbms-engineering.md Section 3.2) —
 * 물리→공용 코드 역매핑·PK 이름 정규화·FK→관계 변환(식별/UK 1:1·기수·rule)·그리드 배치·스킵·
 * FK 인덱스 자동 생성(05-editor/01-core.md §6.6 — PG 생성·MySQL 미생성·키 선두 스킵·복합).
 * 전략은 실물(PostgreSQL) — commonTypeCode가 순수 매핑이라 DB 접속이 필요 없다.
 */
class ReverseContentAssemblerTest {

    private final ReverseContentAssembler assembler = new ReverseContentAssembler();
    private final SchemaIntrospector introspector = new PostgresIntrospector();
    private final ObjectMapper mapper = new ObjectMapper();

    private static IntrospectedSchema.IntrospectedColumn col(String name, String type, Integer length,
                                                             Integer precision, Integer scale, boolean nullable,
                                                             String defaultValue, boolean autoIncrement) {
        return new IntrospectedSchema.IntrospectedColumn(
                name, type, length, precision, scale, nullable, defaultValue, autoIncrement, null);
    }

    /** 회원 테이블 — int8 PK(identity)·varchar not null·nullable varchar */
    private static IntrospectedSchema.IntrospectedTable memberTable() {
        return new IntrospectedSchema.IntrospectedTable(
                "member", "회원",
                List.of(
                        col("id", "int8", null, null, null, false, null, true),
                        col("email", "varchar", 255, null, null, false, null, false),
                        col("nickname", "varchar", 100, null, null, true, "'guest'", false)),
                "member_pkey", List.of("id"),
                List.of(new IntrospectedSchema.IntrospectedUnique("uq_member_email", List.of("email"))));
    }

    /** 주문 테이블 — FK member_id→member.id(NOT NULL), 상태 UK */
    private static IntrospectedSchema.IntrospectedTable orderTable() {
        return new IntrospectedSchema.IntrospectedTable(
                "orders", null,
                List.of(
                        col("id", "int8", null, null, null, false, null, true),
                        col("member_id", "int8", null, null, null, false, null, false),
                        col("code", "varchar", 32, null, null, false, null, false)),
                "PRIMARY", List.of("id"), // MySQL 형태의 PK 상수명 — 정규화 대상
                List.of(new IntrospectedSchema.IntrospectedUnique("uq_orders_code", List.of("code"))));
    }

    @Test
    @DisplayName("물리 타입을 공용 논리 코드로 역매핑하고 length·기본값·자동증가를 보존한다")
    void typeMappingAndColumns() {
        IntrospectedSchema schema = new IntrospectedSchema(List.of(memberTable()), List.of());
        JsonNode content = mapper.readTree(assembler.assemble(schema, introspector, "postgresql").content());

        JsonNode columns = content.path("model").path("tables").get(0).path("columns");
        assertThat(columns).hasSize(3);
        assertThat(columns.get(0).path("dataType").asText()).isEqualTo("BIGINT");
        assertThat(columns.get(0).path("autoIncrement").asBoolean()).isTrue();
        assertThat(columns.get(0).path("length").isNull()).isTrue();
        assertThat(columns.get(1).path("dataType").asText()).isEqualTo("VARCHAR");
        assertThat(columns.get(1).path("length").asInt()).isEqualTo(255);
        assertThat(columns.get(2).path("nullable").asBoolean()).isTrue();
        assertThat(columns.get(2).path("defaultValue").asText()).isEqualTo("'guest'");
        // DB 코멘트가 없는 컬럼은 물리명이 논리명이 된다
        assertThat(columns.get(0).path("logicalName").asText()).isEqualTo("id");
    }

    @Test
    @DisplayName("DB COMMENT ≡ 논리명 — 테이블·컬럼 코멘트가 논리명으로 들어오고 comment 필드는 비워둔다")
    void dbCommentBecomesLogicalName() {
        // member 테이블 코멘트 "회원"·email 컬럼은 코멘트 없음, orders는 테이블 코멘트 없음
        IntrospectedSchema.IntrospectedTable withColumnComment = new IntrospectedSchema.IntrospectedTable(
                "member", "회원",
                List.of(
                        col("id", "int8", null, null, null, false, null, true),
                        new IntrospectedSchema.IntrospectedColumn(
                                "email", "varchar", 255, null, null, false, null, false, "이메일")),
                "member_pkey", List.of("id"), List.of());
        JsonNode content = mapper.readTree(assembler.assemble(
                new IntrospectedSchema(List.of(withColumnComment, orderTable()), List.of()), introspector, "postgresql").content());

        JsonNode tables = content.path("model").path("tables");
        // 테이블 — 코멘트가 논리명으로, 없으면 물리명
        assertThat(tables.get(0).path("logicalName").asText()).isEqualTo("회원");
        assertThat(tables.get(1).path("logicalName").asText()).isEqualTo("orders");
        // 컬럼 — 코멘트가 논리명으로, 없으면 물리명
        assertThat(tables.get(0).path("columns").get(1).path("logicalName").asText()).isEqualTo("이메일");
        assertThat(tables.get(0).path("columns").get(0).path("logicalName").asText()).isEqualTo("id");
        // comment 필드는 DB 코멘트와 무관한 문서 설명 — 리버스에서는 채우지 않는다
        assertThat(tables.get(0).path("comment").isNull()).isTrue();
        assertThat(tables.get(0).path("columns").get(1).path("comment").isNull()).isTrue();
    }

    @Test
    @DisplayName("매핑에 없는 타입은 대문자 원문을 코드로 둔다 — 폴백 자가 치유")
    void unknownTypeFallback() {
        IntrospectedSchema.IntrospectedTable table = new IntrospectedSchema.IntrospectedTable(
                "odd", null,
                List.of(col("span", "interval", null, null, null, true, null, false)),
                null, List.of(), List.of());
        JsonNode content = mapper.readTree(
                assembler.assemble(new IntrospectedSchema(List.of(table), List.of()), introspector, "postgresql").content());
        assertThat(content.path("model").path("tables").get(0).path("columns").get(0)
                .path("dataType").asText()).isEqualTo("INTERVAL");
    }

    @Test
    @DisplayName("MySQL PK 상수명(PRIMARY)은 PK_{테이블}로 정규화하고 PG 이름은 유지한다")
    void pkNameNormalization() {
        IntrospectedSchema schema = new IntrospectedSchema(
                List.of(memberTable(), orderTable()), List.of());
        JsonNode content = mapper.readTree(assembler.assemble(schema, introspector, "postgresql").content());

        JsonNode tables = content.path("model").path("tables");
        assertThat(tables.get(0).path("primaryKey").path("name").asText()).isEqualTo("member_pkey");
        assertThat(tables.get(1).path("primaryKey").path("name").asText()).isEqualTo("PK_orders");
        assertThat(tables.get(0).path("uniques").get(0).path("name").asText()).isEqualTo("uq_member_email");
    }

    @Test
    @DisplayName("FK는 비식별 1:N 관계가 된다 — 부모 기수는 FK NOT NULL로 EXACTLY_ONE, 자식 1:N은 에디터 기본값(ONE_OR_MORE), rule은 enum으로")
    void oneToManyRelationship() {
        IntrospectedSchema.IntrospectedFk fk = new IntrospectedSchema.IntrospectedFk(
                "fk_orders_member", "orders", List.of("member_id"),
                "member", List.of("id"), "CASCADE", "NO ACTION");
        JsonNode content = mapper.readTree(
                assembler.assemble(new IntrospectedSchema(List.of(memberTable(), orderTable()), List.of(fk)),
                        introspector, "postgresql").content());

        JsonNode rel = content.path("model").path("relationships").get(0);
        assertThat(rel.path("type").asText()).isEqualTo("ONE_TO_MANY");
        assertThat(rel.path("identifying").asBoolean()).isFalse();
        assertThat(rel.path("parentMultiplicity").asText()).isEqualTo("EXACTLY_ONE");
        assertThat(rel.path("childMultiplicity").asText()).isEqualTo("ONE_OR_MORE");
        assertThat(rel.path("onDelete").asText()).isEqualTo("CASCADE");
        assertThat(rel.path("onUpdate").asText()).isEqualTo("NO_ACTION");
        assertThat(rel.path("fkName").asText()).isEqualTo("fk_orders_member");
        assertThat(rel.path("columnMappings")).hasSize(1);
    }

    @Test
    @DisplayName("FK 컬럼이 자식 PK에 포함되면 식별 1:1, UK와 정확히 일치하면 비식별 1:1이 된다")
    void oneToOneVariants() {
        // 식별 — PK가 FK 컬럼을 포함
        IntrospectedSchema.IntrospectedTable childIdentified = new IntrospectedSchema.IntrospectedTable(
                "member_profile", null,
                List.of(col("member_id", "int8", null, null, null, false, null, false)),
                "PRIMARY", List.of("member_id"), List.of());
        IntrospectedSchema.IntrospectedFk identifying = new IntrospectedSchema.IntrospectedFk(
                "fk_profile_member", "member_profile", List.of("member_id"), "member", List.of("id"),
                "NO ACTION", "NO ACTION");

        // 비식별 1:1 — FK가 UK와 정확히 일치
        IntrospectedSchema.IntrospectedTable childUnique = new IntrospectedSchema.IntrospectedTable(
                "member_setting", null,
                List.of(col("id", "int8", null, null, null, false, null, true),
                        col("member_id", "int8", null, null, null, true, null, false)),
                "PRIMARY", List.of("id"),
                List.of(new IntrospectedSchema.IntrospectedUnique("uq_setting_member", List.of("member_id"))));
        IntrospectedSchema.IntrospectedFk unique = new IntrospectedSchema.IntrospectedFk(
                "fk_setting_member", "member_setting", List.of("member_id"), "member", List.of("id"),
                "SET NULL", "NO ACTION");

        JsonNode content = mapper.readTree(assembler.assemble(new IntrospectedSchema(
                List.of(memberTable(), childIdentified, childUnique), List.of(identifying, unique)),
                introspector, "postgresql").content());

        JsonNode relationships = content.path("model").path("relationships");
        assertThat(relationships.get(0).path("type").asText()).isEqualTo("ONE_TO_ONE");
        assertThat(relationships.get(0).path("identifying").asBoolean()).isTrue();
        assertThat(relationships.get(0).path("childMultiplicity").asText()).isEqualTo("EXACTLY_ONE");
        assertThat(relationships.get(1).path("type").asText()).isEqualTo("ONE_TO_ONE");
        assertThat(relationships.get(1).path("identifying").asBoolean()).isFalse();
        assertThat(relationships.get(1).path("parentMultiplicity").asText()).isEqualTo("ZERO_OR_ONE");
        assertThat(relationships.get(1).path("childMultiplicity").asText()).isEqualTo("ZERO_OR_ONE");
        assertThat(relationships.get(1).path("onDelete").asText()).isEqualTo("SET_NULL");
    }

    @Test
    @DisplayName("diagram.nodes는 4열 그리드 좌표를 주고 notes·viewport는 비어 있다")
    void gridLayout() {
        IntrospectedSchema schema = new IntrospectedSchema(List.of(memberTable(), orderTable()), List.of());
        JsonNode content = mapper.readTree(assembler.assemble(schema, introspector, "postgresql").content());

        JsonNode nodes = content.path("diagram").path("nodes");
        assertThat(nodes).hasSize(2);
        var entries = nodes.properties().iterator();
        JsonNode first = entries.next().getValue();
        JsonNode second = entries.next().getValue();
        assertThat(first.path("x").asInt()).isEqualTo(80);
        assertThat(first.path("y").asInt()).isEqualTo(80);
        assertThat(second.path("x").asInt()).isEqualTo(80 + 360);
        assertThat(second.path("y").asInt()).isEqualTo(80);
        assertThat(second.path("width").isNull()).isTrue();
        assertThat(content.path("diagram").path("notes")).isEmpty();
        assertThat(content.path("diagram").path("viewport").isNull()).isTrue();
    }

    @Test
    @DisplayName("참조 테이블이 없는 FK는 관계에서 제외하고 skipped에 남긴다")
    void skippedFk() {
        IntrospectedSchema.IntrospectedFk dangling = new IntrospectedSchema.IntrospectedFk(
                "fk_ghost", "orders", List.of("x"), "ghost_table", List.of("id"), "CASCADE", "CASCADE");
        ReverseContentAssembler.AssembledContent assembled = assembler.assemble(
                new IntrospectedSchema(List.of(orderTable()), List.of(dangling)), introspector, "postgresql");

        assertThat(assembled.tableCount()).isEqualTo(1);
        assertThat(assembled.relationshipCount()).isZero();
        assertThat(assembled.skipped()).hasSize(1);
        JsonNode content = mapper.readTree(assembled.content());
        assertThat(content.path("model").path("relationships")).isEmpty();
    }

    @Test
    @DisplayName("§6.6 PostgreSQL은 FK 전체 컬럼으로 idx_ 인덱스를 자동 생성한다")
    void fkIndexAutoCreatedForPostgres() {
        IntrospectedSchema.IntrospectedFk fk = new IntrospectedSchema.IntrospectedFk(
                "fk_orders_member", "orders", List.of("member_id"),
                "member", List.of("id"), "CASCADE", "NO ACTION");
        JsonNode content = mapper.readTree(assembler.assemble(
                new IntrospectedSchema(List.of(memberTable(), orderTable()), List.of(fk)),
                introspector, "postgresql").content());

        // member_id는 orders PK(id)·UK(code) 선두가 아니라 자동 인덱스 대상
        JsonNode indexes = content.path("model").path("tables").get(1).path("indexes");
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).path("name").asText()).isEqualTo("idx_orders_member_id");
        JsonNode columns = content.path("model").path("tables").get(1).path("columns");
        assertThat(indexes.get(0).path("columns").get(0).path("columnId").asText())
                .isEqualTo(columns.get(1).path("id").asText()); // 실제 member_id 컬럼을 가리킨다
        assertThat(indexes.get(0).path("columns").get(0).path("order").asText()).isEqualTo("ASC");
    }

    @Test
    @DisplayName("§6.6 MySQL(InnoDB)은 FK 선언만으로 DB가 자식 인덱스를 만든다 — ERD에 남기지 않는다")
    void fkIndexSkippedForMysql() {
        IntrospectedSchema.IntrospectedFk fk = new IntrospectedSchema.IntrospectedFk(
                "fk_orders_member", "orders", List.of("member_id"),
                "member", List.of("id"), "CASCADE", "NO ACTION");
        JsonNode content = mapper.readTree(assembler.assemble(
                new IntrospectedSchema(List.of(memberTable(), orderTable()), List.of(fk)),
                introspector, "mysql").content());

        assertThat(content.path("model").path("tables").get(1).path("indexes")).isEmpty();
        assertThat(content.path("model").path("relationships")).hasSize(1); // 관계 자체는 만든다
    }

    @Test
    @DisplayName("§6.6 FK 선두 컬럼이 PK·UK 선두면 자동 인덱스를 건너뛴다 — 키 백킹 인덱스가 접두를 커버")
    void fkIndexSkippedWhenLeadingColumnCovered() {
        // 식별 — FK 컬럼 member_id가 복합 PK 선두
        IntrospectedSchema.IntrospectedTable identified = new IntrospectedSchema.IntrospectedTable(
                "member_profile", null,
                List.of(
                        col("member_id", "int8", null, null, null, false, null, false),
                        col("seq", "int4", null, null, null, false, null, false)),
                "member_profile_pkey", List.of("member_id", "seq"), List.of());
        IntrospectedSchema.IntrospectedFk identifying = new IntrospectedSchema.IntrospectedFk(
                "fk_profile_member", "member_profile", List.of("member_id"),
                "member", List.of("id"), "NO ACTION", "NO ACTION");

        // UK 커버 — FK 컬럼 member_id가 단일 컬럼 UK 선두
        IntrospectedSchema.IntrospectedTable ukCovered = new IntrospectedSchema.IntrospectedTable(
                "member_setting", null,
                List.of(
                        col("id", "int8", null, null, null, false, null, true),
                        col("member_id", "int8", null, null, null, true, null, false)),
                "member_setting_pkey", List.of("id"),
                List.of(new IntrospectedSchema.IntrospectedUnique("uq_setting_member", List.of("member_id"))));
        IntrospectedSchema.IntrospectedFk ukFk = new IntrospectedSchema.IntrospectedFk(
                "fk_setting_member", "member_setting", List.of("member_id"),
                "member", List.of("id"), "NO ACTION", "NO ACTION");

        JsonNode content = mapper.readTree(assembler.assemble(new IntrospectedSchema(
                List.of(memberTable(), identified, ukCovered), List.of(identifying, ukFk)),
                introspector, "postgresql").content());

        assertThat(content.path("model").path("tables").get(1).path("indexes")).isEmpty();
        assertThat(content.path("model").path("tables").get(2).path("indexes")).isEmpty();
    }

    @Test
    @DisplayName("§6.6 복합 FK(A,B)는 전체 컬럼 복합 인덱스를 만든다 — 이름은 idx_{테이블}_{컬럼…}")
    void compositeFkGetsCompositeIndex() {
        IntrospectedSchema.IntrospectedTable parent = new IntrospectedSchema.IntrospectedTable(
                "tenant_code", null,
                List.of(
                        col("tenant_id", "int8", null, null, null, false, null, false),
                        col("code", "varchar", 32, null, null, false, null, false)),
                "tenant_code_pkey", List.of("tenant_id", "code"), List.of());
        IntrospectedSchema.IntrospectedTable child = new IntrospectedSchema.IntrospectedTable(
                "tenant_order", null,
                List.of(
                        col("id", "int8", null, null, null, false, null, true),
                        col("tenant_id", "int8", null, null, null, false, null, false),
                        col("code", "varchar", 32, null, null, false, null, false)),
                "tenant_order_pkey", List.of("id"), List.of());
        IntrospectedSchema.IntrospectedFk fk = new IntrospectedSchema.IntrospectedFk(
                "fk_tenant_order_code", "tenant_order", List.of("tenant_id", "code"),
                "tenant_code", List.of("tenant_id", "code"), "NO ACTION", "NO ACTION");

        JsonNode content = mapper.readTree(assembler.assemble(
                new IntrospectedSchema(List.of(parent, child), List.of(fk)),
                introspector, "postgresql").content());

        JsonNode indexes = content.path("model").path("tables").get(1).path("indexes");
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).path("name").asText()).isEqualTo("idx_tenant_order_tenant_id_code");
        assertThat(indexes.get(0).path("columns")).hasSize(2); // A,B 둘 다 — 절반 인덱스가 아니다
        JsonNode columns = content.path("model").path("tables").get(1).path("columns");
        assertThat(indexes.get(0).path("columns").get(1).path("columnId").asText())
                .isEqualTo(columns.get(2).path("id").asText()); // 둘째 자리는 code 컬럼
    }
}
