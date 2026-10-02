package net.java21.crowfoot.api.model.service;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 예전 화면이 모르는 내용을 저장하며 지우지 않게 한다 (08-core/02-model.md Section 1.5.2).
 *
 * <p>에디터는 본체를 통째로 저장한다. 배포 뒤에도 열려 있던 예전 화면은 그 뒤에 생긴 항목을 모르므로
 * 본체에서 빼고 저장한다(v1.31을 배포한 날 v1.30 화면이 요구사항을 지웠다). 에디터는 자기가 아는 항목의 키를
 * 비어 있어도 늘 보낸다. 그래서 저장된 본체의 {@code diagram}에 있는 키가 요청에 <b>아예 없으면</b>
 * 그 항목을 모르는 화면이 보낸 것으로 보고 저장된 값을 이어 붙인다.</p>
 */
public final class ContentCarryOver {

    private ContentCarryOver() {
    }

    /**
     * @return 이어 붙인 것이 있으면 새 본체, 없으면 받은 본체 그대로(같은 문자열)
     */
    public static String apply(ObjectMapper mapper, String stored, String incoming) {
        if (stored == null || stored.isBlank()) {
            return incoming;
        }
        JsonNode storedRoot;
        JsonNode incomingRoot;
        try {
            storedRoot = mapper.readTree(stored);
            incomingRoot = mapper.readTree(incoming);
        } catch (JacksonException e) {
            return incoming;
        }
        if (!(storedRoot.path("diagram") instanceof ObjectNode storedDiagram)
                || !(incomingRoot.path("diagram") instanceof ObjectNode incomingDiagram)) {
            return incoming;
        }
        boolean carried = false;
        for (var entry : storedDiagram.properties()) {
            JsonNode value = entry.getValue();
            boolean empty = value.isNull() || (value.isContainer() && value.isEmpty());
            if (!incomingDiagram.has(entry.getKey()) && !empty) {
                incomingDiagram.set(entry.getKey(), value.deepCopy());
                carried = true;
            }
        }
        if (!carried) {
            return incoming;
        }
        pruneRequirementReferences(incomingRoot, incomingDiagram);
        return mapper.writeValueAsString(incomingRoot);
    }

    /** 이어 붙인 요구사항이 그 사이에 지워진 테이블과 그룹을 가리키지 않게 정리한다(에디터의 연쇄 정리와 같다) */
    private static void pruneRequirementReferences(JsonNode root, ObjectNode diagram) {
        if (!(diagram.get("requirements") instanceof ArrayNode requirements)) {
            return;
        }
        Set<String> tableIds = new HashSet<>();
        root.path("model").path("tables").forEach(table -> tableIds.add(table.path("id").asString("")));
        Set<String> areaIds = new HashSet<>();
        diagram.path("areas").forEach(area -> areaIds.add(area.path("id").asString("")));
        for (JsonNode node : requirements) {
            if (!(node instanceof ObjectNode requirement)) {
                continue;
            }
            if (requirement.get("tableIds") instanceof ArrayNode linked) {
                ArrayNode kept = linked.arrayNode();
                linked.forEach(id -> {
                    if (tableIds.contains(id.asString(""))) {
                        kept.add(id);
                    }
                });
                requirement.set("tableIds", kept);
            }
            JsonNode areaId = requirement.get("areaId");
            if (areaId != null && areaId.isString() && !areaIds.contains(areaId.asString())) {
                requirement.putNull("areaId");
            }
        }
    }
}
