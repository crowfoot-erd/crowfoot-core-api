package net.java21.crowfoot.api.model.sqlimport;

import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDL 텍스트 파서 단위 테스트 (05-editor/04-dbms-engineering.md SQL Import v1) —
 * MySQL·PG 방언 fixture로 컬럼·키·FK·코멘트 해석과 관대한 스킵을 검증한다.
 */
class DdlTextParserTest {

    private final DdlTextParser parser = new DdlTextParser();

    private IntrospectedSchema parse(String ddl) {
        return parser.parse(ddl).schema();
    }

    @Test
    @DisplayName("MySQL — 백틱·AUTO_INCREMENT·inline PK/UK·테이블 COMMENT·스키마 한정명")
    void parsesMysqlDialect() {
        IntrospectedSchema schema = parse("""
                CREATE TABLE IF NOT EXISTS `shop`.`members` (
                  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                  `email` VARCHAR(191) NOT NULL COMMENT '로그인 이메일',
                  `grade` VARCHAR(10) NOT NULL DEFAULT 'basic',
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_members_email` (`email`),
                  KEY `idx_members_grade` (`grade`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='회원';
                """);

        assertThat(schema.tables()).hasSize(1);
        IntrospectedSchema.IntrospectedTable member = schema.tables().get(0);
        assertThat(member.name()).isEqualTo("members"); // db.tbl → 마지막 조각
        assertThat(member.comment()).isEqualTo("회원"); // 테이블 옵션 COMMENT
        assertThat(member.primaryKeyColumns()).containsExactly("id");
        assertThat(member.uniques()).hasSize(1);
        assertThat(member.uniques().get(0).name()).isEqualTo("uk_members_email");

        assertThat(member.columns()).hasSize(4);
        IntrospectedSchema.IntrospectedColumn id = member.columns().get(0);
        assertThat(id.typeName()).isEqualTo("bigint");
        assertThat(id.autoIncrement()).isTrue();
        assertThat(id.nullable()).isFalse();
        IntrospectedSchema.IntrospectedColumn email = member.columns().get(1);
        assertThat(email.typeName()).isEqualTo("varchar");
        assertThat(email.length()).isEqualTo(191);
        assertThat(email.comment()).isEqualTo("로그인 이메일");
        IntrospectedSchema.IntrospectedColumn grade = member.columns().get(2);
        assertThat(grade.defaultValue()).isEqualTo("basic"); // 문자열 리터럴은 따옴표 제거 값
        assertThat(member.columns().get(3).defaultValue()).isEqualTo("current_timestamp");

        // 일반 인덱스(KEY …)는 v1 읽기 범위 밖 — skipped에 남는다
        assertThat(parser.parse("""
                CREATE TABLE t (a INT, KEY idx_a (a));
                """).skipped()).anyMatch(s -> s.contains("idx_a"));
    }

    @Test
    @DisplayName("PG — quoted 식별자·serial→int4 자동증가·numeric(p,s)·COMMENT ON 표준")
    void parsesPostgresDialect() {
        IntrospectedSchema schema = parse("""
                CREATE TABLE "users" (
                  "id" bigserial PRIMARY KEY,
                  name character varying(100) NOT NULL,
                  balance numeric(10,2),
                  deleted_at timestamp with time zone,
                  CONSTRAINT uk_users_name UNIQUE (name)
                );
                ALTER TABLE users ADD CONSTRAINT fk_orders_users
                  FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
                COMMENT ON TABLE users IS '회원';
                COMMENT ON COLUMN users.name IS '표시 이름';
                """);

        IntrospectedSchema.IntrospectedTable users = schema.tables().get(0);
        assertThat(users.comment()).isEqualTo("회원");
        IntrospectedSchema.IntrospectedColumn id = users.columns().get(0);
        assertThat(id.typeName()).isEqualTo("int8"); // bigserial 별칭
        assertThat(id.autoIncrement()).isTrue();
        assertThat(users.columns().get(1).typeName()).isEqualTo("varchar"); // character varying 별칭
        assertThat(users.columns().get(2).precision()).isEqualTo(10);
        assertThat(users.columns().get(2).scale()).isEqualTo(2);
        assertThat(users.columns().get(3).typeName()).isEqualTo("timestamp");
        assertThat(users.columns().get(1).comment()).isEqualTo("표시 이름"); // COMMENT ON COLUMN

        assertThat(schema.foreignKeys()).hasSize(1);
        IntrospectedSchema.IntrospectedFk fk = schema.foreignKeys().get(0);
        assertThat(fk.name()).isEqualTo("fk_orders_users");
        assertThat(fk.childColumns()).containsExactly("user_id");
        assertThat(fk.parentColumns()).containsExactly("id");
        assertThat(fk.onDelete()).isEqualTo("CASCADE");
    }

    @Test
    @DisplayName("inline REFERENCES — 부모 컬럼 목록이 없으면 부모 PK로 채운다")
    void fillsParentColumnsFromParentPrimaryKey() {
        IntrospectedSchema schema = parse("""
                CREATE TABLE teams (id INT PRIMARY KEY, name VARCHAR(50));
                CREATE TABLE players (
                  id INT PRIMARY KEY,
                  team_id INT NOT NULL REFERENCES teams ON DELETE SET NULL
                );
                """);

        assertThat(schema.foreignKeys()).hasSize(1);
        IntrospectedSchema.IntrospectedFk fk = schema.foreignKeys().get(0);
        assertThat(fk.parentTable()).isEqualTo("teams");
        assertThat(fk.parentColumns()).containsExactly("id"); // 부모 PK
        assertThat(fk.childColumns()).containsExactly("team_id");
        assertThat(fk.onDelete()).isEqualTo("SET NULL");
    }

    @Test
    @DisplayName("ALTER TABLE — 복합 PK·UK·FK 제약 추가, REFERENCES 컬럼 생략 해석")
    void parsesAlterTableConstraints() {
        IntrospectedSchema schema = parse("""
                CREATE TABLE order_items (order_id INT, line_no INT, product_id INT NOT NULL);
                ALTER TABLE order_items ADD PRIMARY KEY (order_id, line_no);
                ALTER TABLE order_items ADD CONSTRAINT uk_order_items_product UNIQUE (product_id);
                ALTER TABLE order_items ADD CONSTRAINT fk_order_items_product
                  FOREIGN KEY (product_id) REFERENCES products;
                """);

        IntrospectedSchema.IntrospectedTable items = schema.tables().get(0);
        assertThat(items.primaryKeyColumns()).containsExactly("order_id", "line_no");
        assertThat(items.uniques()).extracting(IntrospectedSchema.IntrospectedUnique::name)
                .containsExactly("uk_order_items_product");
        assertThat(schema.foreignKeys()).hasSize(1);
        // 부모 products가 없는 DDL — 부모 PK를 모르니 1:1 대칭으로 둔다(조립기가 스킵 노트를 남긴다)
        assertThat(schema.foreignKeys().get(0).parentColumns()).containsExactly("product_id");
    }

    @Test
    @DisplayName("관대한 파싱 — 주석·미지원 문장은 skipped, CREATE TABLE은 끝까지 읽는다")
    void skipsUnsupportedStatementsAndKeepsGoing() {
        String ddl = """
                -- 선행 주석
                SET FOREIGN_KEY_CHECKS = 0; /* 블록
                주석 */ # mysql 한 줄 주석
                DROP TABLE IF EXISTS old;
                INSERT INTO t VALUES (1);
                CREATE INDEX idx_t_a ON t (a);
                CREATE VIEW v AS SELECT 1;
                CREATE TABLE keep_me (id INT); -- 후행 주석
                """;
        DdlTextParser.DdlParseResult result = parser.parse(ddl);

        assertThat(result.schema().tables()).hasSize(1);
        assertThat(result.schema().tables().get(0).name()).isEqualTo("keep_me");
        assertThat(result.skipped()).anyMatch(s -> s.startsWith("DROP"))
                .anyMatch(s -> s.startsWith("INSERT"))
                .anyMatch(s -> s.startsWith("CREATE INDEX"))
                .anyMatch(s -> s.startsWith("CREATE VIEW"));
    }

    @Test
    @DisplayName("빈 입력·CREATE TABLE 0개 — 테이블 없는 스키마")
    void emptyDdlYieldsNoTables() {
        assertThat(parse("").tables()).isEmpty();
        assertThat(parse("DROP TABLE x;").tables()).isEmpty();
        assertThat(parse(null).tables()).isEmpty();
    }

    @Test
    @DisplayName("CREATE TABLE AS SELECT — 정의를 읽을 수 없어 skipped")
    void createTableAsSelectIsSkipped() {
        DdlTextParser.DdlParseResult result = parser.parse("CREATE TABLE archive AS SELECT * FROM orders;");

        assertThat(result.schema().tables()).isEmpty();
        assertThat(result.skipped()).singleElement()
                .satisfies(s -> assertThat(s).contains("archive").contains("AS SELECT"));
    }

    @Test
    @DisplayName("MySQL 컬럼 속성 ON UPDATE CURRENT_TIMESTAMP는 FK 규칙이 아니다")
    void columnOnUpdateCurrentTimestampIsNotReferentialAction() {
        IntrospectedSchema schema = parse("""
                CREATE TABLE posts (
                  id INT PRIMARY KEY,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                );
                """);

        assertThat(schema.tables().get(0).columns().get(1).defaultValue()).isEqualTo("current_timestamp");
        assertThat(schema.foreignKeys()).isEmpty();
    }

    @Test
    @DisplayName("CHECK·GENERATED 컬럼은 컬럼째 버리지 않고 절만 건너뛴다")
    void checkAndGeneratedColumnsSurvive() {
        IntrospectedSchema schema = parse("""
                CREATE TABLE accounts (
                  id INT PRIMARY KEY,
                  kind VARCHAR(10) CHECK (kind IN ('free', 'paid')),
                  total INT GENERATED ALWAYS AS (id * 2) STORED,
                  memo VARCHAR(100)
                );
                """);

        assertThat(schema.tables().get(0).columns()).extracting(IntrospectedSchema.IntrospectedColumn::name)
                .containsExactly("id", "kind", "total", "memo");
        assertThat(schema.tables().get(0).columns().get(1).typeName()).isEqualTo("varchar");
    }

    @Test
    @DisplayName("식별 관계 재료 — FK 컬럼이 자식 PK에 편입된 DDL을 그대로 읽는다")
    void readsIdentifyingFkInsidePrimaryKey() {
        IntrospectedSchema schema = parse("""
                CREATE TABLE orders (order_id INT NOT NULL, user_id INT NOT NULL, PRIMARY KEY (order_id, user_id));
                CREATE TABLE users (id INT PRIMARY KEY);
                """);

        IntrospectedSchema.IntrospectedTable orders = schema.tables().get(0);
        assertThat(orders.primaryKeyColumns()).containsExactly("order_id", "user_id");
        assertThat(orders.columns()).allSatisfy(c -> assertThat(c.nullable()).isFalse());
        assertThat(schema.tables().get(1).primaryKeyColumns()).containsExactly("id");
    }
}
