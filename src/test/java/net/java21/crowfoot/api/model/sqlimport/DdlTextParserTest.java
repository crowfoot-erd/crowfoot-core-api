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
        assertThat(member.columns().get(3).defaultValue()).isEqualTo("CURRENT_TIMESTAMP"); // 단어는 원문 대소문자

        // 일반 인덱스(KEY …)는 v1 읽기 범위 밖 — skipped에 남는다
        // 일반 인덱스(KEY …)는 인덱스로 읽는다(v1.34)
        assertThat(parser.parse("""
                CREATE TABLE t (a INT, KEY idx_a (a));
                """).schema().tables().get(0).indexes()).extracting(IntrospectedSchema.IntrospectedIndex::name)
                .containsExactly("idx_a");
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

        assertThat(schema.tables().get(0).columns().get(1).defaultValue()).isEqualTo("CURRENT_TIMESTAMP");
        assertThat(schema.tables().get(0).columns().get(1).onUpdate()).isEqualTo("CURRENT_TIMESTAMP");
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

    @Test
    @DisplayName("v1.34 — 인덱스·FULLTEXT 파서·CHECK·생성 컬럼·ON UPDATE·소수 초·VARBINARY 길이·경고")
    void readsV134Scope() {
        DdlTextParser.DdlParseResult result = parser.parse("""
                SET FOREIGN_KEY_CHECKS = 0;
                CREATE TABLE posts (
                  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                  title VARCHAR(200) NOT NULL,
                  body MEDIUMTEXT NULL,
                  email_enc VARBINARY(512) NOT NULL,
                  status VARCHAR(15) NOT NULL DEFAULT 'DRAFT',
                  hash CHAR(64) AS (CASE WHEN status = 'PUBLISHED' THEN sha2(title, 256) ELSE NULL END) STORED,
                  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                  qty INT CHECK (qty >= 0),
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_posts_hash (hash),
                  KEY idx_posts_status (status, updated_at DESC),
                  KEY idx_posts_title_prefix (title(20)),
                  FULLTEXT KEY ft_posts_title (title, body) WITH PARSER ngram,
                  CONSTRAINT ck_posts_status CHECK (status IN ('DRAFT', 'PUBLISHED'))
                );
                CREATE INDEX idx_posts_qty ON posts (qty);
                """);
        IntrospectedSchema.IntrospectedTable posts = result.schema().tables().get(0);
        assertThat(posts.primaryKeyName()).isEqualTo("posts_pk");
        assertThat(posts.columns().get(3).length()).isEqualTo(512);
        assertThat(posts.columns().get(4).defaultValue()).isEqualTo("DRAFT");
        IntrospectedSchema.IntrospectedColumn hash = posts.columns().get(5);
        assertThat(hash.generatedExpression()).isEqualTo("CASE WHEN status = 'PUBLISHED' THEN sha2(title, 256) ELSE NULL END");
        assertThat(hash.generatedStored()).isTrue();
        IntrospectedSchema.IntrospectedColumn updatedAt = posts.columns().get(6);
        assertThat(updatedAt.precision()).isEqualTo(6);
        assertThat(updatedAt.defaultValue()).isEqualTo("CURRENT_TIMESTAMP(6)");
        assertThat(updatedAt.onUpdate()).isEqualTo("CURRENT_TIMESTAMP(6)");
        assertThat(posts.checks()).extracting(IntrospectedSchema.IntrospectedCheck::name, IntrospectedSchema.IntrospectedCheck::expression)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("posts_qty_check", "qty >= 0"),
                        org.assertj.core.groups.Tuple.tuple("ck_posts_status", "status IN ('DRAFT', 'PUBLISHED')"));
        assertThat(posts.indexes()).extracting(IntrospectedSchema.IntrospectedIndex::name)
                .containsExactly("idx_posts_status", "idx_posts_title_prefix", "ft_posts_title", "idx_posts_qty");
        assertThat(posts.indexes().get(0).columns().get(1).order()).isEqualTo("DESC");
        assertThat(posts.indexes().get(2).type()).isEqualTo("FULLTEXT");
        assertThat(posts.indexes().get(2).parser()).isEqualTo("ngram");
        assertThat(result.warnings()).anyMatch(w -> w.contains("UNSIGNED")).anyMatch(w -> w.contains("접두 길이(20)"));
        assertThat(result.skipped()).anyMatch(s -> s.contains("세션 문장"));
    }

    @Test
    @DisplayName("PG 연산자는 붙은 그대로 — !~·~*·!~*·@>·->> (신고 44), 부호 붙은 숫자는 떼어 읽는다")
    void keepsPostgresOperatorsTogether() {
        IntrospectedSchema.IntrospectedTable table = parse("""
                CREATE TABLE member (
                  handle varchar(30) NOT NULL,
                  tags jsonb,
                  score int,
                  CONSTRAINT ck_handle CHECK (handle !~ '^(go|gi)_'),
                  CONSTRAINT ck_handle_ci CHECK (handle !~* 'x' AND handle ~* 'y'),
                  CONSTRAINT ck_tags CHECK (tags @> '{}' AND tags->>'k' IS NOT NULL),
                  CONSTRAINT ck_score CHECK (score>-1 AND score >= -5)
                );
                """).tables().get(0);
        assertThat(table.checks()).extracting(IntrospectedSchema.IntrospectedCheck::expression).containsExactly(
                "handle !~ '^(go|gi)_'",
                "handle !~* 'x' AND handle ~* 'y'",
                "tags @> '{}' AND tags ->> 'k' IS NOT NULL",
                "score > -1 AND score >= -5");
    }

    @Test
    @DisplayName("빈 문자열 기본값과 PG 캐스트 — ''::character varying은 빈 문자열이고, 캐스트 타입을 컬럼 옵션으로 읽지 않는다")
    void readsEmptyStringDefaultWithCast() {
        DdlTextParser.DdlParseResult result = parser.parse("""
                CREATE TABLE post (
                  title varchar(200) NOT NULL DEFAULT '',
                  body text DEFAULT ''::character varying NOT NULL,
                  tags text[] DEFAULT '{}'::text[],
                  memo text DEFAULT NULL::text
                );
                """);
        List<IntrospectedSchema.IntrospectedColumn> columns = result.schema().tables().get(0).columns();
        assertThat(columns).extracting(IntrospectedSchema.IntrospectedColumn::defaultValue)
                .containsExactly("", "", "{}", null);
        assertThat(columns.get(1).nullable()).isFalse();
        assertThat(result.warnings()).noneMatch(w -> w.contains("CHARACTER SET"));
    }

    @Test
    @DisplayName("IDENTITY 종류 — 인라인 GENERATED ALWAYS와 pg_dump의 ALTER TABLE ONLY … ADD GENERATED ALWAYS(신고 44)")
    void readsIdentityGeneration() {
        List<IntrospectedSchema.IntrospectedColumn> inline = parse("""
                CREATE TABLE a (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, b bigint GENERATED BY DEFAULT AS IDENTITY);
                """).tables().get(0).columns();
        assertThat(inline).extracting(IntrospectedSchema.IntrospectedColumn::autoIncrement).containsExactly(true, true);
        assertThat(inline).extracting(IntrospectedSchema.IntrospectedColumn::identityAlways).containsExactly(true, false);

        DdlTextParser.DdlParseResult dump = parser.parse("""
                CREATE TABLE public.post (id bigint NOT NULL, title character varying(200) DEFAULT ''::character varying NOT NULL);
                ALTER TABLE public.post ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
                    SEQUENCE NAME public.post_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1
                );
                ALTER TABLE ONLY public.post ADD CONSTRAINT post_pkey PRIMARY KEY (id);
                """);
        IntrospectedSchema.IntrospectedTable post = dump.schema().tables().get(0);
        assertThat(post.columns().get(0).autoIncrement()).isTrue();
        assertThat(post.columns().get(0).identityAlways()).isTrue();
        assertThat(post.primaryKeyColumns()).containsExactly("id");
        assertThat(dump.skipped()).isEmpty();
    }
}
