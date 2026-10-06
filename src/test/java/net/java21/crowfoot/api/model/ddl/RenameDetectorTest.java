package net.java21.crowfoot.api.model.ddl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 마이그레이션 이름 변경 감지 (v1.35 — 문서 버전 기록의 같은 id로 예전 이름을 찾는다) */
class RenameDetectorTest {

    private static DdlContent.Column column(String id, String name) {
        return new DdlContent.Column(id, name, "VARCHAR", 50, null, null, true, null, false, null);
    }

    private static DdlContent.Table table(String id, String name, DdlContent.Column... columns) {
        return new DdlContent.Table(id, name, null, List.of(columns), null, List.of(), List.of());
    }

    private static DdlContent doc(DdlContent.Table... tables) {
        return new DdlContent(List.of(tables), List.of());
    }

    @Test
    @DisplayName("테이블과 컬럼 이름 변경을 함께 찾고, DB 본체를 지금 이름으로 맞춘다")
    void detectsTableAndColumnRenames() {
        DdlContent previous = doc(table("t1", "author", column("c1", "id"), column("c2", "legacy_note")));
        DdlContent document = doc(table("t1", "writer", column("c1", "id"), column("c2", "memo")));
        DdlContent db = doc(table("x", "author", column("y1", "id"), column("y2", "legacy_note")));

        RenameDetector.Result result = RenameDetector.detect(db, document, List.of(previous));

        assertThat(result.renames()).containsExactly(
                new RenameDetector.Rename(RenameDetector.KIND_TABLE, null, "author", "writer"),
                new RenameDetector.Rename(RenameDetector.KIND_COLUMN, "writer", "legacy_note", "memo"));
        assertThat(result.adjustedFrom().tables().get(0).physicalName()).isEqualTo("writer");
        assertThat(result.adjustedFrom().tables().get(0).columns()).extracting(DdlContent.Column::physicalName)
                .containsExactly("id", "memo");
        assertThat(SchemaDiffer.diff(result.adjustedFrom(), document, false).changes()).isEmpty();
    }

    @Test
    @DisplayName("예전 이름을 지금 문서의 다른 컬럼이 쓰거나 버전 기록이 없으면 이름 변경으로 보지 않는다")
    void skipsAmbiguousOrUnknown() {
        DdlContent previous = doc(table("t1", "author", column("c1", "a"), column("c2", "b")));
        // c1이 b로, c2가 a로 — 맞바꾼 경우
        DdlContent swapped = doc(table("t1", "author", column("c1", "b"), column("c2", "a")));
        DdlContent db = doc(table("x", "author", column("y1", "a"), column("y2", "b")));
        assertThat(RenameDetector.detect(db, swapped, List.of(previous)).renames()).isEmpty();

        DdlContent added = doc(table("t1", "author", column("c1", "a"), column("c3", "memo")));
        assertThat(RenameDetector.detect(db, added, List.of()).renames()).isEmpty();
    }
}
