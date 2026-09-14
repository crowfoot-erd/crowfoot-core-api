package net.java21.crowfoot.api.model.ddl;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Canonical content → {@link DdlContent} 파서 — model 계층(테이블·관계)만 읽는다.
 *
 * <p>관대한 해석이 원칙: 빠진 필드는 기본값(null·빈 목록·NO_ACTION), 물리명이 없는
 * 테이블·컬럼은 건너뛴다. 존재하지 않는 컬럼 id 참조(삭제 cascade 잔여 등)는
 * 생성 단계에서 조용히 제외된다 — 생성은 최선의 해석이지 검증이 아니다.
 */
public final class ErdContentParser {

    private ErdContentParser() {
    }

    /** 루트 객체의 model 하위를 해석한다 — diagram은 읽지 않는다 */
    public static DdlContent parse(JsonNode root) {
        JsonNode model = root.path("model");
        return new DdlContent(tables(model.path("tables")), relationships(model.path("relationships")));
    }

    private static List<DdlContent.Table> tables(JsonNode array) {
        return items(array, table -> {
            String physicalName = text(table, "physicalName");
            if (physicalName == null) {
                return null;
            }
            List<DdlContent.Column> columns = items(table.path("columns"), ErdContentParser::column);
            return new DdlContent.Table(
                    text(table, "id"),
                    physicalName,
                    // DB COMMENT의 원천은 논리명이다 — content의 comment 필드는 문서 설명이라 읽지 않는다
                    text(table, "logicalName"),
                    columns,
                    keyConstraint(table.path("primaryKey")),
                    items(table.path("uniques"), ErdContentParser::keyConstraint),
                    items(table.path("indexes"), ErdContentParser::index));
        });
    }

    private static DdlContent.Column column(JsonNode node) {
        String physicalName = text(node, "physicalName");
        String dataType = text(node, "dataType");
        if (physicalName == null || dataType == null) {
            return null;
        }
        return new DdlContent.Column(
                text(node, "id"),
                physicalName,
                dataType,
                intOrNull(node, "length"),
                intOrNull(node, "precision"),
                intOrNull(node, "scale"),
                node.path("nullable").asBoolean(true),
                text(node, "defaultValue"),
                node.path("autoIncrement").asBoolean(false),
                text(node, "logicalName"));
    }

    /** PK·UK — 이름·컬럼 목록이 둘 다 있어야 의미가 있다 */
    private static DdlContent.KeyConstraint keyConstraint(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String name = text(node, "name");
        List<String> columnIds = strings(node.path("columnIds"));
        if (name == null || columnIds.isEmpty()) {
            return null;
        }
        return new DdlContent.KeyConstraint(name, columnIds);
    }

    private static DdlContent.Index index(JsonNode node) {
        String name = text(node, "name");
        List<DdlContent.IndexColumn> columns = items(node.path("columns"), column -> {
            String columnId = text(column, "columnId");
            return columnId == null ? null
                    : new DdlContent.IndexColumn(columnId, column.path("order").asText("ASC"));
        });
        if (name == null || columns.isEmpty()) {
            return null;
        }
        return new DdlContent.Index(name, columns);
    }

    private static List<DdlContent.Relationship> relationships(JsonNode array) {
        return items(array, rel -> {
            String fkName = text(rel, "fkName");
            String parentTableId = text(rel, "parentTableId");
            String childTableId = text(rel, "childTableId");
            List<DdlContent.ColumnMapping> mappings = items(rel.path("columnMappings"), mapping -> {
                String parent = text(mapping, "parentColumnId");
                String child = text(mapping, "childColumnId");
                return parent == null || child == null ? null
                        : new DdlContent.ColumnMapping(parent, child);
            });
            if (fkName == null || parentTableId == null || childTableId == null || mappings.isEmpty()) {
                return null;
            }
            return new DdlContent.Relationship(
                    fkName, parentTableId, childTableId, mappings,
                    text(rel, "onDelete"), text(rel, "onUpdate"));
        });
    }

    /* ---------- 관대한 접근 헬퍼 ---------- */

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                if (item.isTextual()) {
                    out.add(item.asText());
                }
            }
        }
        return out;
    }

    private static <T> List<T> items(JsonNode array, Function<JsonNode, T> mapper) {
        List<T> out = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                T mapped = mapper.apply(item);
                if (mapped != null) {
                    out.add(mapped);
                }
            }
        }
        return List.copyOf(out);
    }
}
