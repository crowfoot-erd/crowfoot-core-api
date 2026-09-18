package net.java21.crowfoot.api.model.ddl;

import net.java21.crowfoot.api.model.ddl.DdlGenerator.Result;
import net.java21.crowfoot.api.model.ddl.DdlGenerator.Warning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDL 생성 — 방언별 표기·조립 골격·경고 (05-editor/04-dbms-engineering.md §3.1).
 * JSON fixture를 파서→생성기 파이프라인으로 통과시켜 검증한다.
 */
class DdlGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SAMPLE = """
            {
              "schemaVersion": 1,
              "model": {
                "tables": [
                  {
                    "id": "t-member", "physicalName": "member", "logicalName": "회원",
                    "columns": [
                      {"id": "c-mid", "physicalName": "id", "dataType": "BIGINT", "nullable": false, "autoIncrement": true},
                      {"id": "c-email", "physicalName": "email", "dataType": "VARCHAR", "length": 255, "nullable": false, "logicalName": "이메일"},
                      {"id": "c-point", "physicalName": "point", "dataType": "DECIMAL", "precision": 10, "scale": 2, "nullable": false, "defaultValue": "0"},
                      {"id": "c-active", "physicalName": "active", "dataType": "BOOLEAN", "nullable": false}
                    ],
                    "primaryKey": {"name": "pk_member", "columnIds": ["c-mid"]},
                    "uniques": [{"id": "u-email", "name": "uk_member_email", "columnIds": ["c-email"]}],
                    "indexes": []
                  },
                  {
                    "id": "t-post", "physicalName": "post",
                    "columns": [
                      {"id": "c-pid", "physicalName": "id", "dataType": "BIGINT", "nullable": false, "autoIncrement": true},
                      {"id": "c-pmid", "physicalName": "member_id", "dataType": "BIGINT", "nullable": false},
                      {"id": "c-title", "physicalName": "title", "dataType": "VARCHAR", "length": 200, "nullable": false, "defaultValue": "''"}
                    ],
                    "primaryKey": {"name": "pk_post", "columnIds": ["c-pid"]},
                    "uniques": [],
                    "indexes": [{"id": "i-member", "name": "idx_post_member_id", "columns": [{"columnId": "c-pmid", "order": "ASC"}]}]
                  }
                ],
                "relationships": [
                  {
                    "id": "r1", "fkName": "fk_post_member", "parentTableId": "t-member", "childTableId": "t-post",
                    "columnMappings": [{"parentColumnId": "c-mid", "childColumnId": "c-pmid"}],
                    "onDelete": "CASCADE", "onUpdate": "NO_ACTION"
                  }
                ]
              }
            }
            """;

    private static DdlContent parse(String json) {
        return ErdContentParser.parse(MAPPER.readTree(json));
    }

    /** 샘플 문서를 지정 방언으로 생성 */
    private static Result generate(String json, String dialectId, String modelName) {
        return DdlGenerator.generate(parse(json), Dialects.byId(dialectId),
                DbmsTemplates.byId(dialectId).label(), modelName);
    }

    private static Result generateSample(String dialectId) {
        return generate(SAMPLE, dialectId, null);
    }

    private static ObjectNode tableById(ArrayNode tables, String id) {
        for (tools.jackson.databind.JsonNode table : tables) {
            if (id.equals(table.path("id").asText())) {
                return (ObjectNode) table;
            }
        }
        throw new IllegalArgumentException("테이블 없음: " + id);
    }

    @Test
    @DisplayName("MySQL — AUTO_INCREMENT·타입 매핑·인라인 코멘트·PK/UK 제약·테이블 코멘트")
    void mysqlDialect() {
        Result result = generate(SAMPLE, "mysql", "회원 주문 ERD");

        assertThat(result.sql()).contains("-- 회원 주문 ERD — MySQL DDL");
        assertThat(result.sql()).contains(String.join("\n", List.of(
                "CREATE TABLE member (",
                "    id BIGINT NOT NULL AUTO_INCREMENT,",
                "    email VARCHAR(255) NOT NULL COMMENT '이메일',",
                "    point DECIMAL(10,2) NOT NULL DEFAULT 0,",
                "    active TINYINT(1) NOT NULL,",
                "    CONSTRAINT pk_member PRIMARY KEY (id),",
                "    CONSTRAINT uk_member_email UNIQUE (email)",
                ") COMMENT='회원';")));
        assertThat(result.sql()).contains("    title VARCHAR(200) NOT NULL DEFAULT ''");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("MySQL — BOOLEAN이 TINYINT(1)로 매핑될 때 이중 괄호가 붙지 않는다")
    void mysqlNoDoubleParenthesis() {
        assertThat(generateSample("mysql").sql())
                .contains("active TINYINT(1) NOT NULL")
                .doesNotContain("TINYINT(1)(");
    }

    @Test
    @DisplayName("타입 보강 — NUMERIC(p,s)·PG TINYINT→SMALLINT·TIMESTAMP→TIMESTAMPTZ·Oracle NUMERIC→NUMBER")
    void typeAuditAdditions() {
        String modified = SAMPLE.replace(
                "{\"id\": \"c-active\", \"physicalName\": \"active\", \"dataType\": \"BOOLEAN\", \"nullable\": false}",
                "{\"id\": \"c-active\", \"physicalName\": \"active\", \"dataType\": \"BOOLEAN\", \"nullable\": false},\n"
                        + "                      {\"id\": \"c-ratio\", \"physicalName\": \"ratio\", \"dataType\": \"NUMERIC\", \"precision\": 8, \"scale\": 3},\n"
                        + "                      {\"id\": \"c-sort\", \"physicalName\": \"sort_order\", \"dataType\": \"TINYINT\"},\n"
                        + "                      {\"id\": \"c-at\", \"physicalName\": \"created_at\", \"dataType\": \"TIMESTAMP\"}");
        assertThat(generate(modified, "postgres", null).sql())
                .contains("ratio NUMERIC(8,3)") // NUMERIC도 DECIMAL과 같은 (p,s) 부착
                .contains("sort_order SMALLINT") // PG에 tinyint는 없다
                .contains("created_at TIMESTAMPTZ"); // UTC 순간 타입 — DATETIME(TIMESTAMP)과 라벨이 갈라진다
        assertThat(generate(modified, "oracle", null).sql())
                .contains("ratio NUMBER(8,3)"); // Oracle 숫자 계열은 NUMBER로 모은다
    }

    @Test
    @DisplayName("DB COMMENT의 원천은 논리명 — comment 필드는 문서 설명이라 DDL에 나가지 않는다")
    void commentSourceIsLogicalName() {
        String modified = SAMPLE.replace(
                "\"id\": \"c-email\", \"physicalName\": \"email\", \"dataType\": \"VARCHAR\", \"length\": 255, \"nullable\": false, \"logicalName\": \"이메일\"",
                "\"id\": \"c-email\", \"physicalName\": \"email\", \"dataType\": \"VARCHAR\", \"length\": 255, \"nullable\": false, \"logicalName\": \"이메일\", \"comment\": \"문서 설명\"");

        assertThat(generate(modified, "mysql", null).sql())
                .contains("COMMENT '이메일'")
                .doesNotContain("문서 설명");
    }

    @Test
    @DisplayName("논리명이 비면 코멘트 없이 생성한다")
    void blankLogicalNameOmitsComment() {
        String modified = SAMPLE.replace(
                "\"id\": \"t-member\", \"physicalName\": \"member\", \"logicalName\": \"회원\"",
                "\"id\": \"t-member\", \"physicalName\": \"member\", \"logicalName\": \"\"");

        assertThat(generate(modified, "mysql", null).sql())
                .doesNotContain("COMMENT='회원'");
    }

    @Test
    @DisplayName("PostgreSQL — IDENTITY 자동 증가·COMMENT ON 문장·BYTEA 매핑")
    void postgresDialect() {
        Result result = generateSample("postgres");

        assertThat(result.sql())
                .contains("    id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY")
                .contains("COMMENT ON TABLE member IS '회원';")
                .contains("COMMENT ON COLUMN member.email IS '이메일';");

        String withBlob = SAMPLE.replace(
                "{\"id\": \"c-active\", \"physicalName\": \"active\", \"dataType\": \"BOOLEAN\", \"nullable\": false}",
                "{\"id\": \"c-active\", \"physicalName\": \"active\", \"dataType\": \"BOOLEAN\", \"nullable\": false},\n"
                        + "                      {\"id\": \"c-blob\", \"physicalName\": \"avatar\", \"dataType\": \"BLOB\"}");
        assertThat(generate(withBlob, "postgres", null).sql()).contains("avatar BYTEA");
    }

    @Test
    @DisplayName("Oracle — VARCHAR2에 길이 부착·NUMBER(19) 매핑값 우선·COMMENT ON")
    void oracleDialect() {
        assertThat(generateSample("oracle").sql())
                .contains("    email VARCHAR2(255) NOT NULL")
                .contains("    id NUMBER(19) NOT NULL GENERATED BY DEFAULT AS IDENTITY")
                .contains("    active NUMBER(1) NOT NULL")
                .doesNotContain("NUMBER(19)(")
                .contains("COMMENT ON TABLE member IS '회원';");
    }

    @Test
    @DisplayName("SQL Server — IDENTITY(1,1)·BIT 매핑·코멘트는 줄 주석(쉼표 뒤)")
    void mssqlDialect() {
        assertThat(generateSample("mssql").sql())
                .contains("    id BIGINT NOT NULL IDENTITY(1,1)")
                .contains("    active BIT NOT NULL")
                // 테이블 코멘트는 CREATE 앞 줄 주석
                .contains("-- 회원\nCREATE TABLE member (")
                // 컬럼 줄 주석은 쉼표 뒤 — 주석이 쉼표를 삼키지 않는다
                .contains("    email VARCHAR(255) NOT NULL, -- 이메일");
    }

    @Test
    @DisplayName("미등록 DBMS 코드는 공용 방언 + COMMON_DIALECT 경고")
    void commonFallback() {
        Result result = generateSample("mariadb");

        assertThat(result.sql())
                .contains("    active BOOLEAN NOT NULL")
                .contains("-- 회원\nCREATE TABLE member (");
        assertThat(result.sql()).doesNotContain("AUTO_INCREMENT");
        assertThat(result.warnings())
                .anySatisfy(w -> {
                    assertThat(w.code()).isEqualTo(Warning.COMMON_DIALECT);
                    assertThat(w.message()).contains("공용(논리)");
                });
    }

    @Test
    @DisplayName("FK는 CREATE 이후 ALTER로 — ON DELETE CASCADE·NO_ACTION 절 생략")
    void foreignKeyAfterCreates() {
        String sql = generateSample("mysql").sql();

        int createEnd = sql.indexOf(") COMMENT=");
        String fk = "ALTER TABLE post ADD CONSTRAINT fk_post_member FOREIGN KEY (member_id) "
                + "REFERENCES member (id) ON DELETE CASCADE;";
        assertThat(sql).contains(fk);
        assertThat(sql.indexOf(fk)).isGreaterThan(createEnd);
        assertThat(sql).doesNotContain("ON UPDATE");
    }

    @Test
    @DisplayName("SET_NULL은 SET NULL로 표기하고 onUpdate도 함께 내보낸다")
    void setNullAction() {
        String modified = SAMPLE
                .replace("\"onDelete\": \"CASCADE\"", "\"onDelete\": \"SET_NULL\"")
                .replace("\"onUpdate\": \"NO_ACTION\"", "\"onUpdate\": \"RESTRICT\"");

        assertThat(generate(modified, "postgres", null).sql())
                .contains("FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE SET NULL ON UPDATE RESTRICT;");
    }

    @Test
    @DisplayName("상호 참조 A↔B — CREATE가 먼저고 FK ALTER는 그 뒤라 순서 문제가 없다")
    void circularReference() {
        String modified = SAMPLE.replace(
                "  \"relationships\": [",
                "  \"relationships\": [\n"
                        + "    {\"id\": \"r2\", \"fkName\": \"fk_member_post\", \"parentTableId\": \"t-post\", \"childTableId\": \"t-member\","
                        + " \"columnMappings\": [{\"parentColumnId\": \"c-pid\", \"childColumnId\": \"c-mid\"}]},");
        // r2 매핑은 실제와 무관 — 순서 검증이 목적이다
        modified = modified.replace(
                "\"columnMappings\": [{\"parentColumnId\": \"c-pid\", \"childColumnId\": \"c-mid\"}]",
                "\"columnMappings\": [{\"parentColumnId\": \"c-pid\", \"childColumnId\": \"c-email\"}]");

        String sql = generate(modified, "mysql", null).sql();
        int lastCreate = sql.lastIndexOf("CREATE TABLE");
        assertThat(sql.indexOf("ALTER TABLE post ADD CONSTRAINT fk_post_member")).isGreaterThan(lastCreate);
        assertThat(sql.indexOf("ALTER TABLE member ADD CONSTRAINT fk_member_post")).isGreaterThan(lastCreate);
    }

    @Test
    @DisplayName("복합 PK·복합 UK — columnIds 정의 순서를 그대로 보존한다")
    void compositeKeys() {
        String doc = """
                {"schemaVersion":1,"model":{"tables":[
                  {"id":"t1","physicalName":"order_line",
                   "columns":[
                     {"id":"o1","physicalName":"order_no","dataType":"BIGINT","nullable":false},
                     {"id":"o2","physicalName":"line_no","dataType":"INT","nullable":false},
                     {"id":"o3","physicalName":"product_code","dataType":"VARCHAR","length":30,"nullable":false}],
                   "primaryKey":{"name":"pk_order_line","columnIds":["o1","o2"]},
                   "uniques":[{"id":"u1","name":"uk_order_line_code","columnIds":["o3","o1"]}],
                   "indexes":[]}
                ],"relationships":[]}}
                """;

        assertThat(generate(doc, "mysql", null).sql())
                .contains("CONSTRAINT pk_order_line PRIMARY KEY (order_no, line_no)")
                .contains("CONSTRAINT uk_order_line_code UNIQUE (product_code, order_no)");
    }

    @Test
    @DisplayName("복합 FK — columnMappings 순서(부모 PK 정의 순서)로 양쪽 컬럼을 맞춘다")
    void compositeForeignKey() {
        ObjectNode root = (ObjectNode) MAPPER.readTree(SAMPLE);
        ArrayNode tables = (ArrayNode) root.path("model").path("tables");
        ObjectNode member = tableById(tables, "t-member");
        ObjectNode post = tableById(tables, "t-post");

        // member PK 복합화 + post에 member_no 추가
        ObjectNode pk = member.putObject("primaryKey");
        pk.put("name", "pk_member");
        pk.putArray("columnIds").add("c-mid").add("c-email");
        ObjectNode memberNo = ((ArrayNode) post.path("columns")).addObject();
        memberNo.put("id", "c-mno");
        memberNo.put("physicalName", "member_no");
        memberNo.put("dataType", "INT");
        memberNo.put("nullable", false);

        // 매핑 순서 — email→member_id, id→member_no
        ObjectNode rel = (ObjectNode) root.path("model").path("relationships").path(0);
        ArrayNode mappings = rel.putArray("columnMappings");
        mappings.addObject().put("parentColumnId", "c-email").put("childColumnId", "c-pmid");
        mappings.addObject().put("parentColumnId", "c-mid").put("childColumnId", "c-mno");

        assertThat(generate(root.toString(), "mysql", null).sql())
                .contains("FOREIGN KEY (member_id, member_no) REFERENCES member (email, id)");
    }

    @Test
    @DisplayName("인덱스는 컬럼별 정렬 표기를 포함한다")
    void indexWithOrder() {
        assertThat(generateSample("mysql").sql())
                .contains("CREATE INDEX idx_post_member_id ON post (member_id ASC);");
    }

    @Test
    @DisplayName("nullable 컬럼은 NULL 표기를 생략하고 NOT NULL만 내보낸다")
    void nullableOmission() {
        String modified = SAMPLE.replace(
                "{\"id\": \"c-title\", \"physicalName\": \"title\", \"dataType\": \"VARCHAR\", \"length\": 200, \"nullable\": false, \"defaultValue\": \"''\"}",
                "{\"id\": \"c-title\", \"physicalName\": \"title\", \"dataType\": \"VARCHAR\", \"length\": 200, \"nullable\": false, \"defaultValue\": \"''\"},\n"
                        + "                      {\"id\": \"c-memo\", \"physicalName\": \"memo\", \"dataType\": \"TEXT\"}");

        assertThat(generate(modified, "mysql", null).sql())
                .containsPattern("\\n {4}memo TEXT,?\\n")
                .doesNotContain("memo TEXT NULL");
    }

    @Test
    @DisplayName("컬럼 없는 테이블은 생성에서 제외하고 EMPTY_TABLE 경고")
    void emptyTableSkipped() {
        ObjectNode root = (ObjectNode) MAPPER.readTree(SAMPLE);
        ObjectNode empty = ((ArrayNode) root.path("model").path("tables")).addObject();
        empty.put("id", "t-empty");
        empty.put("physicalName", "empty_one");

        Result result = generate(root.toString(), "mysql", null);

        assertThat(result.sql()).doesNotContain("empty_one");
        assertThat(result.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo(Warning.EMPTY_TABLE);
            assertThat(w.message()).contains("empty_one");
        });
    }

    @Test
    @DisplayName("검증 오류(컬럼 물리명 중복)는 경고로 함께 돌아간다")
    void validationWarning() {
        String modified = SAMPLE.replace(
                "{\"id\": \"c-title\", \"physicalName\": \"title\", \"dataType\": \"VARCHAR\", \"length\": 200, \"nullable\": false, \"defaultValue\": \"''\"}",
                "{\"id\": \"c-title\", \"physicalName\": \"title\", \"dataType\": \"VARCHAR\", \"length\": 200, \"nullable\": false, \"defaultValue\": \"''\"},\n"
                        + "                      {\"id\": \"c-dup\", \"physicalName\": \"title\", \"dataType\": \"TEXT\"}");

        assertThat(generate(modified, "mysql", null).warnings())
                .anySatisfy(w -> {
                    assertThat(w.code()).isEqualTo(Warning.VALIDATION);
                    assertThat(w.message()).contains("post.title");
                });
    }

    @Test
    @DisplayName("테이블이 없으면 헤더만 돌아간다")
    void emptyDocument() {
        Result result = generate("{\"schemaVersion\":1,\"model\":{\"tables\":[],\"relationships\":[]}}",
                "mysql", null);

        assertThat(result.sql()).isEqualTo("-- MySQL DDL");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("빠진 필드는 관대하게 해석한다 — uniques·indexes·onDelete 누락, nullable 기본 true")
    void lenientParsing() {
        String doc = """
                {"schemaVersion":1,"model":{"tables":[
                  {"id":"t1","physicalName":"t",
                   "columns":[{"id":"c1","physicalName":"a","dataType":"INT"}]}
                ],"relationships":[]}}
                """;

        Result result = generate(doc, "postgres", null);

        assertThat(result.sql())
                .contains("CREATE TABLE t (\n    a INTEGER\n);")
                .doesNotContain("NOT NULL");
        assertThat(result.warnings()).isEmpty();
    }
}
