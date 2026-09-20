package net.java21.crowfoot.api.model.ddl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스키마 차이 계산 (05-editor/04-dbms-engineering.md §3.3) — 매칭 규칙·변경 분류·skipIndexes.
 * 순수 함수라 DdlContent 레코드를 직접 조립해 검증한다.
 */
class SchemaDifferTest {

    /* ---------- fixture 빌더 ---------- */

    private static DdlContent.Column column(String id, String name) {
        return new DdlContent.Column(id, name, "VARCHAR", 255, null, null, true, null, false, null);
    }

    private static DdlContent.Column column(String id, String name, String type, boolean nullable) {
        return new DdlContent.Column(id, name, type, null, null, null, nullable, null, false, null);
    }

    private static DdlContent.Table table(String id, String name, DdlContent.Column... columns) {
        return new DdlContent.Table(id, name, null, List.of(columns), null, List.of(), List.of());
    }

    private static DdlContent content(DdlContent.Table... tables) {
        return new DdlContent(List.of(tables), List.of());
    }

    private static DdlContent.Table copyWithPrimaryKey(DdlContent.Table table, DdlContent.KeyConstraint primaryKey) {
        return new DdlContent.Table(table.id(), table.physicalName(), table.logicalName(), table.columns(),
                primaryKey, table.uniques(), table.indexes());
    }

    private static <T extends SchemaDiffer.Change> List<T> of(SchemaDiffer.Result result, Class<T> type) {
        return result.changes().stream().filter(type::isInstance).map(type::cast).toList();
    }

    /* ---------- 테이블·컬럼 ---------- */

    @Test
    @DisplayName("테이블 추가·삭제 — 물리명 기준, 대소문자·공백은 같은 테이블로 본다")
    void tableAddAndDropAreCaseInsensitive() {
        DdlContent from = content(table("t1", "Users", column("c1", "id")));
        DdlContent to = content(table("t9", " users ", column("c1", "id")), table("t2", "orders"));

        SchemaDiffer.Result result = SchemaDiffer.diff(from, to, false);

        assertThat(of(result, SchemaDiffer.TableAdded.class))
                .extracting(added -> added.table().physicalName()).containsExactly("orders");
        assertThat(of(result, SchemaDiffer.TableDropped.class)).isEmpty();
    }

    @Test
    @DisplayName("사라진 테이블은 TableDropped — 남은 테이블의 하위 차이는 계산하지 않는다")
    void droppedTableHasNoSubChanges() {
        DdlContent from = content(table("t1", "users", column("c1", "id")),
                table("t2", "orders", column("c2", "id")));
        DdlContent to = content(table("t1", "users", column("c1", "id")));

        SchemaDiffer.Result result = SchemaDiffer.diff(from, to, false);

        assertThat(of(result, SchemaDiffer.TableDropped.class))
                .extracting(dropped -> dropped.table().physicalName()).containsExactly("orders");
        assertThat(result.changes()).hasSize(1);
    }

    @Test
    @DisplayName("컬럼 추가·삭제·변경 — 변경 필드는 TYPE·NULLABLE로 분류된다")
    void columnAddDropAlter() {
        DdlContent from = content(table("t1", "users",
                column("c1", "id", "BIGINT", false),
                column("c2", "email"),
                column("c3", "phone")));
        DdlContent to = content(table("t1", "users",
                column("x1", "id", "BIGINT", false),
                column("x2", "email", "VARCHAR", false),
                column("x4", "grade")));

        SchemaDiffer.Result result = SchemaDiffer.diff(from, to, false);

        assertThat(of(result, SchemaDiffer.ColumnAdded.class))
                .extracting(added -> added.column().physicalName()).containsExactly("grade");
        assertThat(of(result, SchemaDiffer.ColumnDropped.class))
                .extracting(dropped -> dropped.column().physicalName()).containsExactly("phone");
        List<SchemaDiffer.ColumnAltered> altered = of(result, SchemaDiffer.ColumnAltered.class);
        assertThat(altered).hasSize(1);
        assertThat(altered.get(0).changedFields())
                .containsExactlyInAnyOrder(SchemaDiffer.FIELD_TYPE, SchemaDiffer.FIELD_NULLABLE);
    }

    @Test
    @DisplayName("개명은 remove+add다 — 이름이 컬럼의 정체성이다")
    void renameIsDropAndAdd() {
        DdlContent from = content(table("t1", "users", column("c1", "name")));
        DdlContent to = content(table("t1", "users", column("c2", "full_name")));

        SchemaDiffer.Result result = SchemaDiffer.diff(from, to, false);

        assertThat(of(result, SchemaDiffer.ColumnDropped.class))
                .extracting(dropped -> dropped.column().physicalName()).containsExactly("name");
        assertThat(of(result, SchemaDiffer.ColumnAdded.class))
                .extracting(added -> added.column().physicalName()).containsExactly("full_name");
        assertThat(of(result, SchemaDiffer.ColumnAltered.class)).isEmpty();
    }

    @Test
    @DisplayName("기본값 ''≡null 정규화 — 빈 문자열과 null은 같은 기본값이다. 0은 다르다")
    void defaultEmptyStringEqualsNull() {
        DdlContent.Column emptyDefault = new DdlContent.Column("c1", "point", "DECIMAL", null, 10, 2,
                false, "", false, null);
        DdlContent.Column nullDefault = new DdlContent.Column("c2", "point", "DECIMAL", null, 10, 2,
                false, null, false, null);
        DdlContent.Column zeroDefault = new DdlContent.Column("c3", "point", "DECIMAL", null, 10, 2,
                false, "0", false, null);

        SchemaDiffer.Result normalized = SchemaDiffer.diff(
                content(table("t1", "users", emptyDefault)), content(table("t1", "users", nullDefault)), false);
        SchemaDiffer.Result changed = SchemaDiffer.diff(
                content(table("t1", "users", emptyDefault)), content(table("t1", "users", zeroDefault)), false);

        assertThat(normalized.changes()).isEmpty();
        assertThat(of(changed, SchemaDiffer.ColumnAltered.class).get(0).changedFields())
                .containsExactly(SchemaDiffer.FIELD_DEFAULT);
    }

    @Test
    @DisplayName("논리명만 바뀐 컬럼은 ColumnAltered가 아니라 CommentRefresh다")
    void logicalNameChangeIsCommentRefresh() {
        DdlContent.Column before = new DdlContent.Column("c1", "email", "VARCHAR", 255, null, null,
                false, null, false, "이메일");
        DdlContent.Column after = new DdlContent.Column("c2", "email", "VARCHAR", 255, null, null,
                false, null, false, "메일 주소");

        SchemaDiffer.Result result = SchemaDiffer.diff(
                content(table("t1", "users", before)), content(table("t1", "users", after)), false);

        assertThat(of(result, SchemaDiffer.ColumnAltered.class)).isEmpty();
        assertThat(of(result, SchemaDiffer.CommentRefresh.class))
                .extracting(refresh -> refresh.columnPhysicalName()).containsExactly("email");
    }

    /* ---------- PK·UK ---------- */

    @Test
    @DisplayName("PK는 컬럼 목록이 정체성 — 이름만 다르면 변경이 아니다(MySQL PRIMARY 정규화 흡수)")
    void pkIdentityIsColumnList() {
        DdlContent.Table from = copyWithPrimaryKey(table("t1", "users", column("c1", "id")),
                new DdlContent.KeyConstraint("pk_users", List.of("c1")));
        DdlContent.Table to = copyWithPrimaryKey(table("t1", "users", column("x1", "id")),
                new DdlContent.KeyConstraint("PK_users", List.of("x1")));

        SchemaDiffer.Result result = SchemaDiffer.diff(content(from), content(to), false);

        assertThat(of(result, SchemaDiffer.KeyAltered.class)).isEmpty();
    }

    @Test
    @DisplayName("PK 컬럼이 달라지면(단일→복합) drop+add 재구성이다")
    void pkColumnChangeIsRebuild() {
        DdlContent.Table from = copyWithPrimaryKey(
                table("t1", "users", column("c1", "id"), column("c2", "email")),
                new DdlContent.KeyConstraint("pk_users", List.of("c1")));
        DdlContent.Table to = copyWithPrimaryKey(
                table("t1", "users", column("x1", "id"), column("x2", "email")),
                new DdlContent.KeyConstraint("pk_users", List.of("x1", "x2")));

        SchemaDiffer.Result result = SchemaDiffer.diff(content(from), content(to), false);

        List<SchemaDiffer.KeyAltered> altered = of(result, SchemaDiffer.KeyAltered.class);
        assertThat(altered).hasSize(1);
        assertThat(altered.get(0).kind()).isEqualTo(SqlDialect.KIND_PRIMARY);
        assertThat(altered.get(0).before().name()).isEqualTo("pk_users");
        assertThat(altered.get(0).after().columnIds()).containsExactly("x1", "x2");
    }

    @Test
    @DisplayName("UK는 이름이 정체성 — 신규 이름은 add-only, 사라진 이름은 drop-only, 컬럼 변경은 재구성")
    void ukIdentityIsName() {
        DdlContent.Table from = new DdlContent.Table("t1", "users", null,
                List.of(column("c1", "id"), column("c2", "email"), column("c3", "phone")), null,
                List.of(new DdlContent.KeyConstraint("uk_email", List.of("c2")),
                        new DdlContent.KeyConstraint("uk_phone", List.of("c3"))),
                List.of());
        DdlContent.Table to = new DdlContent.Table("t1", "users", null,
                List.of(column("x1", "id"), column("x2", "email"), column("x3", "phone")), null,
                List.of(new DdlContent.KeyConstraint("uk_email", List.of("x2")),
                        new DdlContent.KeyConstraint("uk_new", List.of("x3"))),
                List.of());

        SchemaDiffer.Result result = SchemaDiffer.diff(content(from), content(to), false);

        assertThat(of(result, SchemaDiffer.KeyAltered.class)).hasSize(2); // uk_phone drop-only + uk_new add-only
        assertThat(of(result, SchemaDiffer.KeyAltered.class)).anySatisfy(altered -> {
            assertThat(altered.before()).isNotNull();
            assertThat(altered.after()).isNull();
            assertThat(altered.before().name()).isEqualTo("uk_phone");
        });
        assertThat(of(result, SchemaDiffer.KeyAltered.class)).anySatisfy(altered -> {
            assertThat(altered.before()).isNull();
            assertThat(altered.after().name()).isEqualTo("uk_new");
        });
    }

    /* ---------- FK ---------- */

    private static DdlContent memberPost(String fkName, String memberTableId, String postTableId,
                                         String memberColumnId, String postColumnId) {
        DdlContent.Table member = table(memberTableId, "member", column(memberColumnId, "id"));
        DdlContent.Table post = table(postTableId, "post", column("pc1", "id"), column(postColumnId, "member_id"));
        return new DdlContent(List.of(member, post), List.of(new DdlContent.Relationship(
                fkName, memberTableId, postTableId,
                List.of(new DdlContent.ColumnMapping(memberColumnId, postColumnId)), "CASCADE", null)));
    }

    @Test
    @DisplayName("FK 폴백 매칭 — fkName이 달라도 (parent, child) 쌍이 같으면 짝으로 잡는다")
    void fkFallbackMatchByNameChange() {
        SchemaDiffer.Result result = SchemaDiffer.diff(
                memberPost("fk_a", "t1", "t2", "mc1", "pc2"),
                memberPost("fk_b", "t9", "t8", "mx1", "px2"), false);

        assertThat(of(result, SchemaDiffer.ForeignKeyAdded.class)).isEmpty();
        assertThat(of(result, SchemaDiffer.ForeignKeyDropped.class)).isEmpty();
    }

    @Test
    @DisplayName("FK 정의 변경(매핑 컬럼)은 drop+add다 — 부모 컬럼이 바뀐 경우")
    void fkDefinitionChangeIsDropAndAdd() {
        // from: member.id → post.member_id / to: member.id → post.owner_id(컬럼 개명 매핑)
        DdlContent from = memberPost("fk_a", "t1", "t2", "mc1", "pc2");
        DdlContent.Table member = table("t9", "member", column("mx1", "id"));
        DdlContent.Table post = table("t8", "post", column("pc1", "id"), column("px2", "owner_id"));
        DdlContent to = new DdlContent(List.of(member, post), List.of(new DdlContent.Relationship(
                "fk_b", "t9", "t8",
                List.of(new DdlContent.ColumnMapping("mx1", "px2")), "CASCADE", null)));

        SchemaDiffer.Result result = SchemaDiffer.diff(from, to, false);

        assertThat(of(result, SchemaDiffer.ForeignKeyDropped.class))
                .extracting(dropped -> dropped.relationship().fkName()).containsExactly("fk_a");
        assertThat(of(result, SchemaDiffer.ForeignKeyAdded.class))
                .extracting(added -> added.relationship().fkName()).containsExactly("fk_b");
    }

    @Test
    @DisplayName("짝이 없는 FK는 추가·삭제 — 사라지는 테이블의 FK는 DROP TABLE이 정리하므로 제외")
    void fkUnmatchedAndDroppedTableFk() {
        DdlContent from = memberPost("fk_a", "t1", "t2", "mc1", "pc2");
        DdlContent to = new DdlContent(List.of(table("t9", "member", column("mx1", "id"))), List.of());

        SchemaDiffer.Result result = SchemaDiffer.diff(from, to, false);

        // post 테이블이 사라졌다 — fk_a는 삭제 문장을 만들지 않는다(TableDropped가 정리한다)
        assertThat(of(result, SchemaDiffer.ForeignKeyDropped.class)).isEmpty();
        assertThat(of(result, SchemaDiffer.TableDropped.class)).hasSize(1);
    }

    /* ---------- 인덱스 ---------- */

    @Test
    @DisplayName("인덱스 변경 — 이름 매칭, 컬럼·정렬이 다르면 drop+add")
    void indexChanges() {
        DdlContent.Table from = new DdlContent.Table("t1", "post", null,
                List.of(column("c1", "id"), column("c2", "member_id")), null, List.of(), List.of(
                new DdlContent.Index("idx_member", List.of(new DdlContent.IndexColumn("c2", "ASC"))),
                new DdlContent.Index("idx_old", List.of(new DdlContent.IndexColumn("c1", "ASC")))));
        DdlContent.Table to = new DdlContent.Table("t1", "post", null,
                List.of(column("x1", "id"), column("x2", "member_id")), null, List.of(), List.of(
                new DdlContent.Index("idx_member", List.of(new DdlContent.IndexColumn("x2", "DESC"))),
                new DdlContent.Index("idx_new", List.of(new DdlContent.IndexColumn("x1", "ASC")))));

        SchemaDiffer.Result result = SchemaDiffer.diff(content(from), content(to), false);

        assertThat(of(result, SchemaDiffer.IndexAdded.class))
                .extracting(added -> added.index().name()).containsExactlyInAnyOrder("idx_member", "idx_new");
        assertThat(of(result, SchemaDiffer.IndexDropped.class))
                .extracting(dropped -> dropped.index().name()).containsExactlyInAnyOrder("idx_member", "idx_old");
    }

    @Test
    @DisplayName("skipIndexes — 인덱스 연산은 목록에서 제외하고 제외 건수만 돌려준다")
    void skipIndexesCountsInsteadOfChanges() {
        DdlContent.Table from = new DdlContent.Table("t1", "post", null,
                List.of(column("c1", "id")), null, List.of(), List.of(
                new DdlContent.Index("idx_old", List.of(new DdlContent.IndexColumn("c1", "ASC")))));
        DdlContent.Table to = new DdlContent.Table("t1", "post", null,
                List.of(column("x1", "id")), null, List.of(), List.of(
                new DdlContent.Index("idx_new", List.of(new DdlContent.IndexColumn("x1", "ASC")))));

        SchemaDiffer.Result skipped = SchemaDiffer.diff(content(from), content(to), true);
        SchemaDiffer.Result included = SchemaDiffer.diff(content(from), content(to), false);

        assertThat(skipped.changes()).isEmpty();          // 인덱스 외 차이가 없다
        assertThat(skipped.skippedIndexChanges()).isEqualTo(2); // idx_old 삭제 + idx_new 추가
        assertThat(of(included, SchemaDiffer.IndexAdded.class)).hasSize(1);
        assertThat(included.skippedIndexChanges()).isZero();
    }
}
