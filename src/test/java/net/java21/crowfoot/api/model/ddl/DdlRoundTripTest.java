package net.java21.crowfoot.api.model.ddl;

import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.MySqlIntrospector;
import net.java21.crowfoot.api.connection.introspect.PostgresIntrospector;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.model.sqlimport.DdlTextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDL 왕복 시험(v1.34 — CHANGELOG v1.34 기획 완료 기준) — 실제 MySQL·PostgreSQL에서
 * "원본 DDL을 그대로 실행한 DB"와 "그 DDL을 가져와 Crowfoot이 생성한 배포 SQL을 실행한 DB"를
 * 리버스로 다시 읽어 비교한다. 차이가 없어야 가져오기·DDL 생성·배포가 원본을 보존한다.
 *
 * <p>MySQL 자료는 사용자 보고에 쓰인 blog 스키마(테이블 40개·FK 74개 — 문자열 기본값, VARBINARY,
 * MEDIUMTEXT, DATETIME(6), ON UPDATE, CHECK, 생성 컬럼, FULLTEXT ngram, 복합·DESC 인덱스)다.
 * docker 데몬이 없으면 스킵한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class DdlRoundTripTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withUsername("root")
            .withPassword("root-test-pass");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReverseContentAssembler assembler = new ReverseContentAssembler();
    private final MySqlIntrospector mysqlIntrospector = new MySqlIntrospector();
    private final PostgresIntrospector postgresIntrospector = new PostgresIntrospector();

    @Test
    @DisplayName("MySQL — blog 스키마: 원본 실행 DB와 Crowfoot 배포 DB의 리버스 결과가 같다")
    void mysqlBlogSchemaRoundTrip() throws Exception {
        String ddl = resource("ddl-roundtrip/blog-schema-mysql.sql");

        try (Connection admin = mysql("")) {
            execute(admin, "CREATE DATABASE original_db");
            execute(admin, "CREATE DATABASE deployed_db");
        }
        try (Connection original = mysql("original_db?allowMultiQueries=true")) {
            execute(original, ddl);
        }

        DdlTextParser.DdlParseResult parsed = new DdlTextParser().parse(ddl);
        DdlContent imported = content(parsed.schema(), mysqlIntrospector, "mysql");
        DdlGenerator.Result generated = DdlGenerator.generate(imported, Dialects.byId("mysql"), "MySQL", "blog 1.0");
        try (Connection deployed = mysql("deployed_db")) {
            for (String statement : generated.statements()) {
                execute(deployed, statement);
            }
        }

        DdlContent fromOriginal;
        DdlContent fromDeployed;
        try (Connection original = mysql("original_db"); Connection deployed = mysql("deployed_db")) {
            fromOriginal = content(mysqlIntrospector.introspect(original, null), mysqlIntrospector, "mysql");
            fromDeployed = content(mysqlIntrospector.introspect(deployed, null), mysqlIntrospector, "mysql");
        }

        assertThat(fromOriginal.tables()).hasSize(40);
        assertThat(fromOriginal.relationships()).hasSize(74);
        assertThat(SchemaDiffer.diff(fromOriginal, fromDeployed, false).changes()).isEmpty();
        // 가져온 문서 자체도 배포된 DB와 같다 — 문서를 다시 배포하거나 비교해도 차이가 없다
        assertThat(SchemaDiffer.diff(imported, fromDeployed, false).changes()).isEmpty();

        // 보고된 결함이 다시 나오지 않는다
        assertThat(generated.sql())
                .contains("role VARCHAR(15) NOT NULL DEFAULT 'USER'")
                .contains("DEFAULT 'DEFAULT'")
                .contains("VARBINARY(512)")
                .contains("MEDIUMTEXT")
                .contains("DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)")
                .contains("WITH PARSER ngram")
                .doesNotContain("DEFAULT USER")
                .doesNotContain("CONSTRAINT PRIMARY");
        long checks = imported.tables().stream().mapToLong(t -> t.checks().size()).sum();
        long indexes = imported.tables().stream().mapToLong(t -> t.indexes().size()).sum();
        long generatedColumns = imported.tables().stream()
                .flatMap(t -> t.columns().stream()).filter(c -> c.generated() != null).count();
        assertThat(checks).isPositive();
        assertThat(indexes).isPositive();
        assertThat(generatedColumns).isPositive();
    }

    @Test
    @DisplayName("PostgreSQL — shop 스키마: 원본 실행 DB와 Crowfoot 배포 DB의 리버스 결과가 같다")
    void postgresShopSchemaRoundTrip() throws Exception {
        String ddl = resource("ddl-roundtrip/shop-schema-postgresql.sql");

        try (Connection admin = postgres(POSTGRES.getDatabaseName())) {
            execute(admin, "CREATE DATABASE original_db");
            execute(admin, "CREATE DATABASE deployed_db");
        }
        try (Connection original = postgres("original_db")) {
            execute(original, ddl);
        }

        DdlTextParser.DdlParseResult parsed = new DdlTextParser().parse(ddl);
        DdlContent imported = content(parsed.schema(), postgresIntrospector, "postgresql");
        DdlGenerator.Result generated = DdlGenerator.generate(imported, Dialects.byId("postgres"), "PostgreSQL", "shop");
        try (Connection deployed = postgres("deployed_db")) {
            for (String statement : generated.statements()) {
                execute(deployed, statement);
            }
        }

        DdlContent fromOriginal;
        DdlContent fromDeployed;
        try (Connection original = postgres("original_db"); Connection deployed = postgres("deployed_db")) {
            fromOriginal = content(postgresIntrospector.introspect(original, "public"), postgresIntrospector, "postgresql");
            fromDeployed = content(postgresIntrospector.introspect(deployed, "public"), postgresIntrospector, "postgresql");
        }

        assertThat(fromOriginal.tables()).hasSize(3);
        assertThat(SchemaDiffer.diff(fromOriginal, fromDeployed, false).changes()).isEmpty();
        assertThat(generated.sql())
                .contains("DEFAULT 'USER'")
                .contains("DEFAULT 'DEFAULT'")
                .contains("DEFAULT 'it''s me'")
                .contains("GENERATED ALWAYS AS")
                .contains("TIMESTAMP(3)");
    }

    @Test
    @DisplayName("MySQL — 마이그레이션: 이름 변경은 RENAME으로 데이터를 지키고, 기존 테이블에 더한 인덱스가 생기며, 반영 뒤 차이가 0이다")
    void mysqlMigrationRenameAndIndex() throws Exception {
        String ddl = """
                CREATE TABLE zz_alt_author (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  name VARCHAR(50) NOT NULL,
                  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                  legacy_note VARCHAR(100) NULL,
                  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_zz_alt_author_name (name)
                );
                CREATE TABLE zz_alt_post (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  author_id BIGINT NOT NULL,
                  title VARCHAR(100) NOT NULL,
                  PRIMARY KEY (id),
                  KEY idx_zz_alt_post_author (author_id),
                  CONSTRAINT fk_zz_alt_post_author FOREIGN KEY (author_id) REFERENCES zz_alt_author (id)
                );
                """;
        try (Connection admin = mysql("")) {
            execute(admin, "CREATE DATABASE migration_db");
        }
        String original = assembler.assemble(new DdlTextParser().parse(ddl).schema(), mysqlIntrospector, "mysql").content();
        DdlContent originalDoc = ErdContentParser.parse(mapper.readTree(original));
        try (Connection db = mysql("migration_db")) {
            for (String statement : DdlGenerator.generate(originalDoc, Dialects.byId("mysql"), "MySQL", "alter-test").statements()) {
                execute(db, statement);
            }
            execute(db, "INSERT INTO zz_alt_author (name, legacy_note) VALUES ('kim', '지켜야 할 메모')");
        }

        // 문서 고치기 — apply_schema와 같은 결과: 이름 변경은 id를 그대로 두고 물리명만 바꾼다
        tools.jackson.databind.node.ObjectNode root = (tools.jackson.databind.node.ObjectNode) mapper.readTree(original);
        tools.jackson.databind.JsonNode tables = root.path("model").path("tables");
        tools.jackson.databind.node.ObjectNode author = null;
        tools.jackson.databind.node.ObjectNode post = null;
        for (tools.jackson.databind.JsonNode table : tables) {
            if ("zz_alt_author".equals(table.path("physicalName").asString())) author = (tools.jackson.databind.node.ObjectNode) table;
            if ("zz_alt_post".equals(table.path("physicalName").asString())) post = (tools.jackson.databind.node.ObjectNode) table;
        }
        for (tools.jackson.databind.JsonNode column : author.path("columns")) {
            if ("legacy_note".equals(column.path("physicalName").asString())) {
                ((tools.jackson.databind.node.ObjectNode) column).put("physicalName", "memo").put("logicalName", "memo");
            }
        }
        ((tools.jackson.databind.node.ArrayNode) author.path("columns")).addObject()
                .put("id", "c-bio").put("physicalName", "bio").put("logicalName", "bio").put("dataType", "VARCHAR")
                .put("length", 200).put("nullable", true).put("autoIncrement", false);
        String titleId = null;
        for (tools.jackson.databind.JsonNode column : post.path("columns")) {
            if ("title".equals(column.path("physicalName").asString())) {
                ((tools.jackson.databind.node.ObjectNode) column).put("length", 200);
                titleId = column.path("id").asString();
            }
        }
        tools.jackson.databind.node.ObjectNode index = ((tools.jackson.databind.node.ArrayNode) post.path("indexes")).addObject();
        index.put("id", "i-title").put("name", "idx_zz_alt_post_title").put("type", "BTREE");
        index.putArray("columns").addObject().put("columnId", titleId).put("order", "ASC");
        DdlContent editedDoc = ErdContentParser.parse(root);

        DdlContent dbBefore;
        try (Connection db = mysql("migration_db")) {
            dbBefore = comparisonContent(mysqlIntrospector.introspect(db, null), mysqlIntrospector, "mysql");
        }
        RenameDetector.Result renames = RenameDetector.detect(dbBefore, editedDoc, List.of(originalDoc));
        MigrationDdlGenerator.Result plan = MigrationDdlGenerator.generate(renames.adjustedFrom(), editedDoc,
                Dialects.byId("mysql"), "MySQL", "alter-test", "DB", "문서", false, renames.renames());

        assertThat(plan.sql())
                .contains("ALTER TABLE zz_alt_author RENAME COLUMN legacy_note TO memo;")
                .contains("CREATE INDEX idx_zz_alt_post_title ON zz_alt_post (title ASC);")
                .contains("MODIFY COLUMN title VARCHAR(200)")
                .contains("ADD COLUMN bio VARCHAR(200)")
                .doesNotContain("DROP COLUMN legacy_note")
                .doesNotContain("NOT_INTROSPECTED");
        assertThat(plan.destructive()).isEmpty();
        assertThat(plan.warnings()).noneMatch(w -> "NOT_INTROSPECTED".equals(w.code()));

        try (Connection db = mysql("migration_db")) {
            for (String statement : plan.safeStatements()) {
                execute(db, statement);
            }
            try (java.sql.ResultSet rs = db.createStatement().executeQuery("SELECT memo FROM zz_alt_author WHERE name = 'kim'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("지켜야 할 메모"); // 이름을 바꿔도 데이터가 남는다
            }
            DdlContent dbAfter = comparisonContent(mysqlIntrospector.introspect(db, null), mysqlIntrospector, "mysql");
            List<SchemaDiffer.Change> remaining = SchemaDiffer.diff(dbAfter, editedDoc, false).changes().stream()
                    .filter(change -> !(change instanceof SchemaDiffer.CommentRefresh))
                    .toList();
            assertThat(remaining).isEmpty();
        }
    }

    @Test
    @DisplayName("PostgreSQL — 신고 44 구조(GIN·연산자 클래스·식·부분 유니크·INCLUDE·!~·IDENTITY ALWAYS·DEFAULT '')가 왕복에서 보존된다")
    void postgresReport44RoundTrip() throws Exception {
        String ddl = resource("ddl-roundtrip/report44-postgresql.sql");

        try (Connection admin = postgres(POSTGRES.getDatabaseName())) {
            execute(admin, "CREATE DATABASE report_original");
            execute(admin, "CREATE DATABASE report_deployed");
        }
        try (Connection original = postgres("report_original")) {
            execute(original, ddl);
        }

        DdlTextParser.DdlParseResult parsed = new DdlTextParser().parse(ddl);
        assertThat(parsed.warnings()).isEmpty();
        DdlContent imported = content(parsed.schema(), postgresIntrospector, "postgresql");
        DdlGenerator.Result generated = DdlGenerator.generate(imported, Dialects.byId("postgres"), "PostgreSQL", "report");
        assertThat(generated.warnings()).noneMatch(w -> w.message().contains("인덱스"));
        try (Connection deployed = postgres("report_deployed")) {
            execute(deployed, "CREATE EXTENSION IF NOT EXISTS pg_trgm"); // 확장은 배포 SQL 밖이다
            for (String statement : generated.statements()) {
                execute(deployed, statement);
            }
        }

        DdlContent fromOriginal;
        DdlContent fromDeployed;
        try (Connection original = postgres("report_original"); Connection deployed = postgres("report_deployed")) {
            fromOriginal = content(postgresIntrospector.introspect(original, "public"), postgresIntrospector, "postgresql");
            fromDeployed = content(postgresIntrospector.introspect(deployed, "public"), postgresIntrospector, "postgresql");
        }

        assertThat(describe(SchemaDiffer.diff(fromOriginal, fromDeployed, false).changes())).isEmpty();
        // 리버스가 특수 인덱스를 읽는다 — 원본 DB 기준
        DdlContent.Table member = fromOriginal.tables().stream().filter(t -> t.physicalName().equals("member")).findFirst().orElseThrow();
        assertThat(member.indexes()).extracting(DdlContent.Index::name)
                .contains("uq_member_nickname", "ix_member_handle_pattern", "ix_member_active");
        assertThat(generated.sql())
                .contains("CREATE UNIQUE INDEX uq_member_nickname ON member (lower(nickname))")
                .contains("CREATE INDEX ix_member_handle_pattern ON member (handle varchar_pattern_ops ASC)")
                .contains("CREATE INDEX ix_member_active ON member (id ASC) WHERE deleted_at IS NULL")
                .contains("CREATE INDEX ix_post_title_trgm ON post USING gin (title gin_trgm_ops)")
                .contains("CREATE INDEX ix_post_author_recent ON post (author_id ASC, created_at DESC) INCLUDE (title)")
                .contains("CREATE UNIQUE INDEX uq_image_profile_current ON image (owner_id ASC) WHERE purpose = 'PROFILE' AND is_current")
                .contains("handle !~ '^(go|gi)_'")
                .contains("handle !~* '^admin'")
                .contains("id BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY")
                .contains("id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY")
                .contains("title VARCHAR(200) NOT NULL DEFAULT ''");
    }

    @Test
    @DisplayName("MySQL — 함수 키 조각 인덱스·함수 유니크·DEFAULT ''·CURRENT_TIMESTAMP 기본값이 왕복에서 보존된다(v1.37)")
    void mysqlFunctionalIndexRoundTrip() throws Exception {
        String ddl = """
                CREATE TABLE member (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  email VARCHAR(100) NOT NULL,
                  nickname VARCHAR(30) NOT NULL DEFAULT '',
                  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uq_member_email_ci ((lower(email))),
                  KEY ix_member_nick ((upper(nickname)), joined_at DESC)
                );
                """;
        try (Connection admin = mysql("")) {
            execute(admin, "CREATE DATABASE fn_original");
            execute(admin, "CREATE DATABASE fn_deployed");
        }
        try (Connection original = mysql("fn_original")) {
            execute(original, ddl);
        }
        DdlTextParser.DdlParseResult parsed = new DdlTextParser().parse(ddl);
        DdlContent imported = content(parsed.schema(), mysqlIntrospector, "mysql");
        DdlGenerator.Result generated = DdlGenerator.generate(imported, Dialects.byId("mysql"), "MySQL", "fn");
        try (Connection deployed = mysql("fn_deployed")) {
            for (String statement : generated.statements()) {
                execute(deployed, statement);
            }
        }
        DdlContent fromOriginal;
        DdlContent fromDeployed;
        try (Connection original = mysql("fn_original"); Connection deployed = mysql("fn_deployed")) {
            fromOriginal = content(mysqlIntrospector.introspect(original, null), mysqlIntrospector, "mysql");
            fromDeployed = content(mysqlIntrospector.introspect(deployed, null), mysqlIntrospector, "mysql");
        }
        assertThat(describe(SchemaDiffer.diff(fromOriginal, fromDeployed, false).changes())).isEmpty();
        assertThat(fromOriginal.tables().get(0).indexes()).extracting(DdlContent.Index::name)
                .containsExactlyInAnyOrder("uq_member_email_ci", "ix_member_nick");
        assertThat(generated.sql())
                .contains("CREATE UNIQUE INDEX uq_member_email_ci ON member ((lower(email)))")
                .contains("DEFAULT ''");
    }

    /* ---------- 헬퍼 ---------- */

    /** 실제 DB와 비교할 때의 조립 — FK 인덱스를 만들어 넣지 않는다(마이그레이션 계획과 같다) */
    private DdlContent comparisonContent(IntrospectedSchema schema, SchemaIntrospector introspector, String databaseType) {
        String json = assembler.assemble(schema, introspector, databaseType, false).content();
        return ErdContentParser.parse(mapper.readTree(json));
    }

    private DdlContent content(IntrospectedSchema schema, SchemaIntrospector introspector, String databaseType) {
        String json = assembler.assemble(schema, introspector, databaseType).content();
        return ErdContentParser.parse(mapper.readTree(json));
    }

    private static Connection mysql(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/" + database, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Connection postgres(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + database, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + "\n--- 실행한 SQL ---\n" + sql, e);
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = DdlRoundTripTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unused")
    private static List<String> describe(List<SchemaDiffer.Change> changes) {
        return changes.stream().map(Object::toString).toList();
    }
}
