package net.java21.crowfoot.api.model.edit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 문서 개요 — 본체를 사람이 읽는 이름 기준의 구조로 바꾼다 (08-core/17-model-edit.md Section 3.1).
 * 좌표와 UUID는 넣지 않는다. 요구사항의 상태 판정(Section 2.3)도 여기서 계산한다.
 */
public final class DocumentOutline {

    private DocumentOutline() {
    }

    /** 요구사항 상태 판정 (Section 2.3) — 저장하는 값은 status, revision, appliedRevision, tableIds뿐이고 판정은 읽을 때 계산한다 */
    public static String state(JsonNode requirement) {
        String status = requirement.path("status").asText("draft");
        boolean hasTables = !requirement.path("tableIds").isEmpty();
        if ("document".equals(requirement.path("scope").asText("tables"))) {
            return switch (status) {
                case "confirmed" -> "APPLIED";
                case "dropped" -> "DROPPED";
                default -> "DRAFT";
            };
        }
        return switch (status) {
            case "dropped" -> hasTables ? "LEFTOVER" : "DROPPED";
            case "confirmed" -> {
                int revision = requirement.path("revision").asInt(1);
                int applied = requirement.path("appliedRevision").asInt(0);
                if (applied < revision) {
                    yield "PENDING";
                }
                yield hasTables ? "APPLIED" : "UNLINKED";
            }
            default -> "DRAFT";
        };
    }

    /** 판정별 요구사항 수와 반영 대기 목록 — 개요와 쓰기 응답이 함께 쓴다 */
    public static Map<String, Object> requirementSummary(JsonNode root) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String state : List.of("APPLIED", "PENDING", "UNLINKED", "LEFTOVER", "DRAFT", "DROPPED")) {
            counts.put(state, 0);
        }
        List<String> pending = new ArrayList<>();
        for (JsonNode requirement : root.path("diagram").path("requirements")) {
            String state = state(requirement);
            counts.merge(state, 1, Integer::sum);
            if ("PENDING".equals(state)) {
                pending.add(requirement.path("code").asText());
            }
        }
        pending.sort(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("pending", pending);
        return out;
    }

    public static Map<String, Object> build(JsonNode root) {
        JsonNode model = root.path("model");
        JsonNode diagram = root.path("diagram");
        Map<String, String> tableNames = new HashMap<>();
        Map<String, Map<String, String>> columnNames = new HashMap<>();
        for (JsonNode table : model.path("tables")) {
            String tableId = table.path("id").asText();
            tableNames.put(tableId, table.path("physicalName").asText());
            Map<String, String> names = new HashMap<>();
            table.path("columns").forEach(column -> names.put(column.path("id").asText(), column.path("physicalName").asText()));
            columnNames.put(tableId, names);
        }
        Map<String, String> areaNames = new HashMap<>();
        Map<String, List<String>> areasOfTable = new HashMap<>();
        List<Map<String, Object>> areas = new ArrayList<>();
        for (JsonNode area : diagram.path("areas")) {
            String name = area.path("name").asText();
            areaNames.put(area.path("id").asText(), name);
            List<String> members = names(area.path("tableIds"), tableNames);
            for (JsonNode id : area.path("tableIds")) {
                areasOfTable.computeIfAbsent(id.asText(), key -> new ArrayList<>()).add(name);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("color", area.path("color").asText("default"));
            item.put("description", area.path("description").asText(""));
            item.put("tables", members);
            areas.add(item);
        }

        Map<String, List<String>> codesOfTable = new HashMap<>();
        Set<String> traced = new HashSet<>();
        List<Map<String, Object>> requirements = new ArrayList<>();
        for (JsonNode requirement : diagram.path("requirements")) {
            String code = requirement.path("code").asText();
            for (JsonNode id : requirement.path("tableIds")) {
                codesOfTable.computeIfAbsent(id.asText(), key -> new ArrayList<>()).add(code);
                traced.add(id.asText());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("domain", requirement.path("areaId").isTextual() ? areaNames.get(requirement.path("areaId").asText()) : null);
            item.put("scope", requirement.path("scope").asText("tables"));
            item.put("title", requirement.path("title").asText(""));
            item.put("description", requirement.path("description").asText(""));
            item.put("status", requirement.path("status").asText("draft"));
            item.put("revision", requirement.path("revision").asInt(1));
            item.put("appliedRevision", requirement.path("appliedRevision").asInt(0));
            item.put("tables", names(requirement.path("tableIds"), tableNames));
            item.put("state", state(requirement));
            requirements.add(item);
        }
        requirements.sort((a, b) -> String.valueOf(a.get("code")).compareTo(String.valueOf(b.get("code"))));

        Map<String, Set<String>> fkColumns = new HashMap<>();
        List<Map<String, Object>> relationships = new ArrayList<>();
        for (JsonNode relationship : model.path("relationships")) {
            String parentId = relationship.path("parentTableId").asText();
            String childId = relationship.path("childTableId").asText();
            List<Map<String, Object>> mappings = new ArrayList<>();
            for (JsonNode mapping : relationship.path("columnMappings")) {
                fkColumns.computeIfAbsent(childId, key -> new HashSet<>()).add(mapping.path("childColumnId").asText());
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("parentColumn", columnNames.getOrDefault(parentId, Map.of()).get(mapping.path("parentColumnId").asText()));
                pair.put("childColumn", columnNames.getOrDefault(childId, Map.of()).get(mapping.path("childColumnId").asText()));
                mappings.add(pair);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", relationship.path("fkName").asText(relationship.path("name").asText("")));
            item.put("parent", tableNames.get(parentId));
            item.put("child", tableNames.get(childId));
            item.put("type", relationship.path("type").asText("ONE_TO_MANY"));
            item.put("identifying", relationship.path("identifying").asBoolean(false));
            item.put("parentMultiplicity", relationship.path("parentMultiplicity").asText("EXACTLY_ONE"));
            item.put("childMultiplicity", relationship.path("childMultiplicity").asText("ZERO_OR_MORE"));
            item.put("onDelete", relationship.path("onDelete").asText("NO_ACTION"));
            item.put("onUpdate", relationship.path("onUpdate").asText("NO_ACTION"));
            item.put("columnMappings", mappings);
            relationships.add(item);
        }

        List<Map<String, Object>> tables = new ArrayList<>();
        List<String> untraced = new ArrayList<>();
        for (JsonNode table : model.path("tables")) {
            String tableId = table.path("id").asText();
            Map<String, String> names = columnNames.get(tableId);
            List<String> pk = DocumentEditor.strings(table.path("primaryKey").path("columnIds"));
            Set<String> fk = fkColumns.getOrDefault(tableId, Set.of());
            List<Map<String, Object>> columns = new ArrayList<>();
            for (JsonNode column : table.path("columns")) {
                String columnId = column.path("id").asText();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("physicalName", column.path("physicalName").asText());
                putNames(item, column.path("logicalName").asText(""));
                item.put("dataType", column.path("dataType").asText(""));
                item.put("length", number(column.path("length")));
                item.put("precision", number(column.path("precision")));
                item.put("scale", number(column.path("scale")));
                item.put("nullable", column.path("nullable").asBoolean(true));
                item.put("defaultValue", column.path("defaultValue").isTextual() ? column.path("defaultValue").asText() : null);
                item.put("autoIncrement", column.path("autoIncrement").asBoolean(false));
                if (column.path("generated").isObject()) {
                    Map<String, Object> generated = new LinkedHashMap<>();
                    generated.put("expression", column.path("generated").path("expression").asText(""));
                    generated.put("stored", column.path("generated").path("stored").asBoolean(true));
                    item.put("generated", generated);
                } else {
                    item.put("generated", null);
                }
                item.put("onUpdate", column.path("onUpdate").isTextual() ? column.path("onUpdate").asText() : null);
                item.put("primaryKey", pk.contains(columnId));
                item.put("foreignKey", fk.contains(columnId));
                item.put("domainType", column.path("domain").path("name").isTextual() ? column.path("domain").path("name").asText() : null);
                columns.add(item);
            }
            List<Map<String, Object>> uniques = new ArrayList<>();
            for (JsonNode unique : table.path("uniques")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", unique.path("name").asText(""));
                item.put("columns", names(unique.path("columnIds"), names));
                uniques.add(item);
            }
            List<Map<String, Object>> indexes = new ArrayList<>();
            for (JsonNode index : table.path("indexes")) {
                List<Map<String, Object>> indexColumns = new ArrayList<>();
                for (JsonNode column : index.path("columns")) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", names.get(column.path("columnId").asText()));
                    item.put("order", column.path("order").asText("ASC"));
                    indexColumns.add(item);
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", index.path("name").asText(""));
                item.put("columns", indexColumns);
                item.put("type", index.path("type").asText("BTREE"));
                item.put("parser", index.path("parser").isTextual() ? index.path("parser").asText() : null);
                indexes.add(item);
            }
            List<Map<String, Object>> checks = new ArrayList<>();
            for (JsonNode check : table.path("checks")) {
                Map<String, Object> checkItem = new LinkedHashMap<>();
                checkItem.put("name", check.path("name").asText(""));
                checkItem.put("expression", check.path("expression").asText(""));
                checks.add(checkItem);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("physicalName", table.path("physicalName").asText());
            putNames(item, table.path("logicalName").asText(""));
            item.put("columns", columns);
            List<String> pkNames = new ArrayList<>();
            pk.forEach(id -> pkNames.add(names.get(id)));
            item.put("primaryKey", pkNames);
            item.put("uniques", uniques);
            item.put("indexes", indexes);
            item.put("checks", checks);
            item.put("areas", areasOfTable.getOrDefault(tableId, List.of()));
            item.put("requirementCodes", codesOfTable.getOrDefault(tableId, List.of()));
            tables.add(item);
            if (!traced.contains(tableId)) {
                untraced.add(table.path("physicalName").asText());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requirements", requirements);
        out.put("tables", tables);
        out.put("relationships", relationships);
        out.put("areas", areas);
        out.put("untracedTables", untraced);
        out.put("requirementSummary", requirementSummary(root));
        // 검증 예외 — 사용자가 의도된 예외로 둔 경고(05-editor/05-validation.md Section 4.4)
        List<Map<String, Object>> exceptions = new ArrayList<>();
        for (JsonNode exception : diagram.path("validationExceptions")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleId", exception.path("ruleId").asText(""));
            item.put("target", exception.path("target").asText(""));
            item.put("reason", exception.path("reason").asText(""));
            item.put("createdBy", exception.path("createdBy").isTextual() ? exception.path("createdBy").asText() : null);
            item.put("createdAt", exception.path("createdAt").isTextual() ? exception.path("createdAt").asText() : null);
            exceptions.add(item);
        }
        out.put("validationExceptions", exceptions);
        return out;
    }

    /** 저장된 logicalName(`논리명-----설명`)을 나눠 싣는다 */
    private static void putNames(Map<String, Object> item, String stored) {
        int cut = stored.indexOf(DocumentEditor.SEPARATOR);
        item.put("logicalName", cut < 0 ? stored : stored.substring(0, cut));
        item.put("description", cut < 0 ? "" : stored.substring(cut + DocumentEditor.SEPARATOR.length()));
    }

    private static Integer number(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static List<String> names(JsonNode ids, Map<String, String> byId) {
        List<String> out = new ArrayList<>();
        for (JsonNode id : ids) {
            String name = byId.get(id.asText());
            if (name != null) {
                out.add(name);
            }
        }
        return out;
    }
}
