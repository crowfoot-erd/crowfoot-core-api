package net.java21.crowfoot.api.model.ddl;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코멘트 비교 — 논리명이 없으면 물리명으로 본다(리버스의 논리명 = 물리명 규칙, v1.36 — 커뮤니티 신고 41).
 */
class CommentDiffTest {

    private static DdlContent.Column column(String name, String logicalName) {
        return new DdlContent.Column("c-" + name, name, "VARCHAR", 100, null, null, true, null, false, logicalName);
    }

    private static DdlContent content(String tableLogical, DdlContent.Column... columns) {
        DdlContent.Table table = new DdlContent.Table("t1", "author", tableLogical, List.of(columns), null, List.of(), List.of());
        return new DdlContent(List.of(table), List.of());
    }

    private static String migrate(DdlContent db, DdlContent document, String dbms) {
        return MigrationDdlGenerator.generate(db, document, Dialects.byId(dbms), dbms, "e2e", "DB", "문서", false).sql();
    }

    @Test
    @DisplayName("코멘트 없는 DB 컬럼(리버스 논리명 = 물리명)과 논리명 없는 문서 컬럼은 같다 — 문장이 없다")
    void blankLogicalNameEqualsPhysicalName() {
        DdlContent db = content("author", column("memo", "memo"));
        DdlContent document = content(null, column("memo", null));
        assertThat(migrate(db, document, "mysql")).doesNotContain("ALTER");
        assertThat(migrate(db, document, "postgres")).doesNotContain("COMMENT ON");
    }

    @Test
    @DisplayName("DB에 남은 다른 코멘트는 문서의 논리명으로 고친다 — 문서에 논리명이 없으면 지운다")
    void staleCommentIsRefreshedOrCleared() {
        DdlContent db = content("author", column("memo", "legacy_note"));
        assertThat(migrate(db, content(null, column("memo", "메모")), "mysql"))
                .contains("ALTER TABLE author MODIFY COLUMN memo VARCHAR(100) COMMENT '메모';");
        assertThat(migrate(db, content(null, column("memo", null)), "mysql"))
                .contains("ALTER TABLE author MODIFY COLUMN memo VARCHAR(100) COMMENT '';");
        assertThat(migrate(db, content(null, column("memo", null)), "postgres"))
                .contains("COMMENT ON COLUMN author.memo IS NULL;");
    }
}
