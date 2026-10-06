package net.java21.crowfoot.api.model.edit;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 반영 대기 요구사항의 바뀐 내용 (08-core/17-model-edit.md Section 2.4 — v1.35).
 *
 * <p>요구사항은 지금 내용만 저장한다. 마지막으로 반영한 내용은 문서 버전 기록에 있다 — 같은 요구사항 id가
 * {@code revision == appliedRevision}이던 가장 최근 버전의 내용이 "반영한 내용"이다. 반영한 적이 없으면
 * ({@code appliedRevision == 0}) 새 요구사항이라 이전 내용이 없다. 버전 기록이 보존 정책으로 지워졌으면
 * 이전 내용을 모른다({@code beforeKnown: false}).
 */
public final class RequirementChanges {

    private RequirementChanges() {
    }

    /** 요구사항 코드 → 바뀐 내용. 반영 대기(PENDING)인 것만. 버전을 넘나드는 대조는 id로 한다 */
    public static Map<String, Map<String, Object>> compute(JsonNode current, List<JsonNode> previousRoots) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, String> tableNames = tableNames(current);
        for (JsonNode requirement : current.path("diagram").path("requirements")) {
            if (!"PENDING".equals(DocumentOutline.state(requirement))) {
                continue;
            }
            String id = requirement.path("id").asText();
            int applied = requirement.path("appliedRevision").asInt(0);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("appliedRevision", applied);
            change.put("revision", requirement.path("revision").asInt(1));
            change.put("after", snapshot(requirement, tableNames));
            if (applied == 0) {
                change.put("isNew", true);
                change.put("beforeKnown", true);
                change.put("before", null);
            } else {
                Map<String, Object> before = null;
                for (JsonNode root : previousRoots) {
                    JsonNode match = find(root, id, applied);
                    if (match != null) {
                        before = snapshot(match, tableNames(root));
                        break;
                    }
                }
                change.put("isNew", false);
                change.put("beforeKnown", before != null);
                change.put("before", before);
            }
            out.put(requirement.path("code").asText(), change);
        }
        return out;
    }

    private static JsonNode find(JsonNode root, String id, int revision) {
        for (JsonNode requirement : root.path("diagram").path("requirements")) {
            if (id.equals(requirement.path("id").asText()) && requirement.path("revision").asInt(1) == revision) {
                return requirement;
            }
        }
        return null;
    }

    private static Map<String, Object> snapshot(JsonNode requirement, Map<String, String> tableNames) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", requirement.path("title").asText(""));
        item.put("description", requirement.path("description").asText(""));
        item.put("status", requirement.path("status").asText("draft"));
        List<String> tables = new ArrayList<>();
        for (JsonNode tableId : requirement.path("tableIds")) {
            String name = tableNames.get(tableId.asText());
            if (name != null) {
                tables.add(name);
            }
        }
        item.put("tables", tables);
        List<Map<String, Object>> criteria = new ArrayList<>();
        for (JsonNode criterion : requirement.path("criteria")) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("text", criterion.path("text").asText(""));
            criteria.add(c);
        }
        item.put("criteria", criteria);
        return item;
    }

    private static Map<String, String> tableNames(JsonNode root) {
        Map<String, String> names = new HashMap<>();
        root.path("model").path("tables").forEach(t -> names.put(t.path("id").asText(), t.path("physicalName").asText()));
        return names;
    }
}
