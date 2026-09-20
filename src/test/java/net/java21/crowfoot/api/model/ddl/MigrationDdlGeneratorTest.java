package net.java21.crowfoot.api.model.ddl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이그레이션 DDL 생성 (05-editor/04-dbms-engineering.md §3.3) — 방언별 ALTER 표기·
 * 파괴 블록 순서·경고. SchemaDiffer→생성기 파이프라인 통합으로 검증한다.
 */
class MigrationDdlGeneratorTest {

    /* ---------- fixture 빌더 ---------- */

    private static DdlContent.Column col(String id, String name, String type, Integer length, boolean nullable,
                                         String defaultValue, String logicalName) {
        return new DdlContent.Column(id, name, type, length, null, null, nullable, defaultValue, false, logicalName);
    }

    private static DdlContent.Table table(String id, String name, String logicalName, List<DdlContent.Column> columns) {
        return new DdlContent.Table(id, name, logicalName, columns, null, List.of(), List.of());
    }

    private static DdlContent single(DdlContent.Table table) {
        return new DdlContent(List.of(table), List.of());
    }

    private static MigrationDdlGenerator.Result generate(DdlContent from, DdlContent to, String dialectId) {
        return MigrationDdlGenerator.generate(from, to, Dialects.byId(dialectId),
                DbmsTemplates.byId(dialectId).label(), "회원 ERD", "v2", "v3", false);
    }

    /** users(email) 한 테이블 — 컬럼 변경 계열 테스트의 공용 베이스 */
    private static DdlContent users(DdlContent.Column email) {
        return single(table("t1", "users", null, List.of(col("c1", "id", "BIGINT", null, false, null, null), email)));
    }

    private static boolean hasWarning(MigrationDdlGenerator.Result result, String code) {
        return result.warnings().stream().anyMatch(w -> w.code().equals(code));
    }

    /* ---------- 추가 계열 ---------- */

    @Test
    @DisplayName("MySQL 컬럼 추가 — 타입 매핑(TINYINT(1))·NOT NULL·인라인 코멘트")
    void mysqlAddColumn() {
        DdlContent from = users(col("c2", "email", "VARCHAR", 255, false, null, "이메일"));
        DdlContent added = single(new DdlContent.Table("t1", "users", null,
                List.of(col("c1", "id", "BIGINT", null, false, null, null),
                        col("c2", "email", "VARCHAR", 255, false, null, "이메일"),
                        col("c3", "active", "BOOLEAN", null, false, null, "활성")),
                null, List.of(), List.of()));

        MigrationDdlGenerator.Result result = generate(from, added, "mysql");

        assertThat(result.sql()).contains("-- 회원 ERD — MySQL 마이그레이션 DDL (v2 → v3)");
        assertThat(result.sql()).contains(
                "ALTER TABLE users ADD COLUMN active TINYINT(1) NOT NULL COMMENT '활성';");
        assertThat(result.statementCount()).isEqualTo(1);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("테이블 추가 — 생성기(§3.1)의 CREATE TABLE 문장을 그대로 재사용한다")
    void tableAddedReusesCreateTable() {
        DdlContent from = new DdlContent(List.of(), List.of());
        DdlContent to = single(table("t2", "point_hist", "포인트 이력",
                List.of(col("c1", "id", "BIGINT", null, false, null, null))));

        MigrationDdlGenerator.Result result = generate(from, to, "mysql");

        assertThat(result.sql()).contains("CREATE TABLE point_hist (");
        assertThat(result.sql()).contains("    id BIGINT NOT NULL");
    }

    /* ---------- 컬럼 변경 — 방언별 문법 ---------- */

    @Test
    @DisplayName("MySQL 컬럼 변경 — MODIFY 전체 재정의(코멘트 보존)")
    void mysqlModifyIsFullRedefinition() {
        DdlContent from = users(col("c2", "email", "VARCHAR", 255, false, "''", "이메일"));
        DdlContent to = users(col("x2", "email", "VARCHAR", 100, false, "''", "이메일"));

        MigrationDdlGenerator.Result result = generate(from, to, "mysql");

        assertThat(result.sql()).contains(
                "ALTER TABLE users MODIFY COLUMN email VARCHAR(100) NOT NULL DEFAULT '' COMMENT '이메일';");
    }

    @Test
    @DisplayName("PostgreSQL 컬럼 변경 — SET TYPE/NOT NULL/DEFAULT 절을 한 문장에 조합한다")
    void postgresClauseCombination() {
        DdlContent from = users(col("c2", "email", "VARCHAR", 255, true, null, null));
        DdlContent to = users(col("x2", "email", "VARCHAR", 100, false, "'none'", null));

        MigrationDdlGenerator.Result result = generate(from, to, "postgres");

        assertThat(result.sql()).contains("ALTER TABLE users "
                + "ALTER COLUMN email TYPE VARCHAR(100), "
                + "ALTER COLUMN email SET NOT NULL, "
                + "ALTER COLUMN email SET DEFAULT 'none';");
    }

    @Test
    @DisplayName("PostgreSQL 컬럼 변경 — 해제 방향은 DROP NOT NULL·DROP DEFAULT")
    void postgresDropClauses() {
        DdlContent from = users(col("c2", "email", "VARCHAR", 255, false, "''", null));
        DdlContent to = users(col("x2", "email", "VARCHAR", 255, true, null, null));

        MigrationDdlGenerator.Result result = generate(from, to, "postgres");

        assertThat(result.sql()).contains("ALTER TABLE users "
                + "ALTER COLUMN email DROP NOT NULL, ALTER COLUMN email DROP DEFAULT;");
    }

    @Test
    @DisplayName("Oracle 컬럼 추가·변경 — ADD (..)·MODIFY (..) 괄호 묶음")
    void oracleAddAndModifyParentheses() {
        DdlContent from = users(col("c2", "email", "VARCHAR", 255, false, null, null));
        DdlContent to = users(col("x2", "email", "VARCHAR", 100, false, null, null));

        MigrationDdlGenerator.Result result = generate(from, to, "oracle");

        assertThat(result.sql()).contains("ALTER TABLE users MODIFY (email VARCHAR2(100) NOT NULL);");
    }

    @Test
    @DisplayName("SQL Server 컬럼 변경 — 기본값은 ALTER COLUMN에 실을 수 없어 VALIDATION 경고")
    void mssqlDefaultChangeWarns() {
        DdlContent from = users(col("c2", "email", "VARCHAR", 255, true, null, null));
        DdlContent to = users(col("x2", "email", "VARCHAR", 255, false, "'none'", null));

        MigrationDdlGenerator.Result result = generate(from, to, "mssql");

        assertThat(result.sql()).contains("ALTER TABLE users ALTER COLUMN email VARCHAR(255) NOT NULL;");
        assertThat(result.sql()).doesNotContain("DEFAULT");
        assertThat(hasWarning(result, DdlGenerator.Warning.VALIDATION)).isTrue();
        assertThat(result.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo(DdlGenerator.Warning.VALIDATION);
            assertThat(w.message()).contains("기본값");
        });
    }

    @Test
    @DisplayName("코멘트 갱신 — PG는 COMMENT ON, MySQL은 MODIFY 전체 재정의로 갱신한다")
    void commentRefreshPerDialect() {
        DdlContent from = users(col("c2", "email", "VARCHAR", 255, false, null, "이메일"));
        DdlContent to = single(table("t1", "users", "회원",
                List.of(col("c1", "id", "BIGINT", null, false, null, null),
                        col("x2", "email", "VARCHAR", 255, false, null, "메일 주소"))));

        assertThat(generate(from, to, "postgres").sql())
                .contains("COMMENT ON TABLE users IS '회원';")
                .contains("COMMENT ON COLUMN users.email IS '메일 주소';");
        assertThat(generate(from, to, "mysql").sql())
                .contains("ALTER TABLE users MODIFY COLUMN email VARCHAR(255) NOT NULL COMMENT '메일 주소';")
                .contains("ALTER TABLE users COMMENT='회원';");
    }

    /* ---------- 파괴 블록 ---------- */

    @Test
    @DisplayName("파괴적 연산은 마지막 별도 블록 — FK drop → 제약 → 컬럼 → 인덱스 → 테이블 순서")
    void destructiveBlockOrder() {
        DdlContent.Table fromPost = new DdlContent.Table("t2", "post", null,
                List.of(col("c3", "id", "BIGINT", null, false, null, null),
                        col("c4", "member_id", "BIGINT", null, false, null, null),
                        col("c5", "legacy", "TEXT", null, true, null, null)),
                null,
                List.of(new DdlContent.KeyConstraint("uk_post_legacy", List.of("c5"))),
                List.of(new DdlContent.Index("idx_post_member", List.of(
                        new DdlContent.IndexColumn("c4", "ASC")))));
        DdlContent.Table fromAudit = table("t3", "audit_log", null,
                List.of(col("c6", "id", "BIGINT", null, false, null, null)));
        DdlContent from = new DdlContent(
                List.of(table("t1", "member", null, List.of(col("c1", "id", "BIGINT", null, false, null, null))),
                        fromPost, fromAudit),
                List.of(new DdlContent.Relationship("fk_post_member", "t1", "t2",
                        List.of(new DdlContent.ColumnMapping("c1", "c4")), null, null)));

        DdlContent.Table toPost = new DdlContent.Table("t2", "post", null,
                List.of(col("x3", "id", "BIGINT", null, false, null, null),
                        col("x4", "member_id", "BIGINT", null, false, null, null)),
                null, List.of(), List.of());
        DdlContent to = new DdlContent(
                List.of(table("t1", "member", null, List.of(col("c1", "id", "BIGINT", null, false, null, null))),
                        toPost),
                List.of());

        MigrationDdlGenerator.Result result = generate(from, to, "postgres");

        String sql = result.sql();
        int banner = sql.indexOf("파괴적 연산");
        int fkDrop = sql.indexOf("ALTER TABLE post DROP CONSTRAINT fk_post_member;");
        int ukDrop = sql.indexOf("ALTER TABLE post DROP CONSTRAINT uk_post_legacy;");
        int columnDrop = sql.indexOf("ALTER TABLE post DROP COLUMN legacy;");
        int indexDrop = sql.indexOf("DROP INDEX idx_post_member;");
        int tableDrop = sql.indexOf("DROP TABLE audit_log;");
        assertThat(fkDrop).isPositive();
        assertThat(banner).isLessThan(fkDrop);
        assertThat(fkDrop).isLessThan(ukDrop);
        assertThat(ukDrop).isLessThan(columnDrop);
        assertThat(columnDrop).isLessThan(indexDrop);
        assertThat(indexDrop).isLessThan(tableDrop);

        assertThat(hasWarning(result, DdlGenerator.Warning.DESTRUCTIVE)).isTrue();
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w.message()).contains("5건"));
        assertThat(result.statementCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("같은 이름의 UK 재구성 — DROP이 ADD보다 먼저 와야 해 add 바로 앞에 붙는다(MySQL DROP INDEX)")
    void sameNameUniqueRebuildIsAdjacent() {
        DdlContent.Table from = new DdlContent.Table("t1", "users", null,
                List.of(col("c1", "id", "BIGINT", null, false, null, null),
                        col("c2", "email", "VARCHAR", 255, false, null, null)),
                null, List.of(new DdlContent.KeyConstraint("uk_email", List.of("c2"))), List.of());
        DdlContent.Table to = new DdlContent.Table("t1", "users", null,
                List.of(col("x1", "id", "BIGINT", null, false, null, null),
                        col("x2", "email", "VARCHAR", 255, false, null, null)),
                null, List.of(new DdlContent.KeyConstraint("uk_email", List.of("x2", "x1"))), List.of());

        String sql = generate(single(from), single(to), "mysql").sql();

        int drop = sql.indexOf("ALTER TABLE users DROP INDEX uk_email;");
        int add = sql.indexOf("ALTER TABLE users ADD CONSTRAINT uk_email UNIQUE (email, id);");
        assertThat(drop).isPositive();
        assertThat(add).isGreaterThan(drop);
        assertThat(sql.indexOf("-- ⚠")).isNegative(); // 파괴 블록 없음 — 인라인 drop이 전부다
    }

    /* ---------- 경고·빈 차이 ---------- */

    @Test
    @DisplayName("skipIndexes — 인덱스 문장은 나가지 않고 NOT_INTROSPECTED 경고가 건수를 안내한다")
    void skipIndexesWarnsNotIntrospected() {
        DdlContent.Table from = new DdlContent.Table("t1", "post", null,
                List.of(col("c1", "id", "BIGINT", null, false, null, null)), null, List.of(), List.of(
                new DdlContent.Index("idx_old", List.of(new DdlContent.IndexColumn("c1", "ASC")))));
        DdlContent.Table to = new DdlContent.Table("t1", "post", null,
                List.of(col("x1", "id", "BIGINT", null, false, null, null)), null, List.of(), List.of(
                new DdlContent.Index("idx_new", List.of(new DdlContent.IndexColumn("x1", "ASC")))));

        MigrationDdlGenerator.Result result = MigrationDdlGenerator.generate(single(from), single(to),
                Dialects.byId("mysql"), "MySQL", null, "DB", "문서", true);

        assertThat(result.sql()).doesNotContain("INDEX");
        assertThat(hasWarning(result, DdlGenerator.Warning.NOT_INTROSPECTED)).isTrue();
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w.message()).contains("2건"));
    }

    @Test
    @DisplayName("차이가 없으면 헤더만 돌아간다 — 문장 0개·경고 없음")
    void identicalContentsHeaderOnly() {
        DdlContent from = users(col("c2", "email", "VARCHAR", 255, false, null, "이메일"));
        DdlContent to = single(table("t1", "users", null,
                List.of(col("c1", "id", "BIGINT", null, false, null, null),
                        col("x2", "email", "VARCHAR", 255, false, null, "이메일"))));

        MigrationDdlGenerator.Result result = generate(from, to, "mysql");

        assertThat(result.sql()).isEqualTo("-- 회원 ERD — MySQL 마이그레이션 DDL (v2 → v3)");
        assertThat(result.statementCount()).isZero();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("공용(논리) 방언 폴백 — COMMON_DIALECT 경고")
    void commonDialectWarning() {
        DdlContent from = users(col("c2", "email", "VARCHAR", 255, false, null, null));
        DdlContent to = users(col("x2", "email", "VARCHAR", 100, false, null, null));

        MigrationDdlGenerator.Result result = generate(from, to, "mariadb");

        assertThat(hasWarning(result, DdlGenerator.Warning.COMMON_DIALECT)).isTrue();
        assertThat(result.sql()).contains("ALTER TABLE users ALTER COLUMN email VARCHAR(100) NOT NULL;");
    }
}
