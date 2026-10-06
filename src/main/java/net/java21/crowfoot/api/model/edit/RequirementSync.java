package net.java21.crowfoot.api.model.edit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.java21.crowfoot.api.model.edit.EditRequests.RequirementItem;
import tools.jackson.databind.JsonNode;

/**
 * 요구사항 동기화 계획 (08-core/17-model-edit.md Section 3.5 — v1.36).
 *
 * <p>기준이 되는 요구사항 전체 목록을 문서의 요구사항과 맞춘다. code가 있으면 code로, 없으면 제목으로 맞춘다
 * (공백을 한 칸으로 줄이고 대소문자를 가리지 않는다). 맞춘 요구사항은 입력에 있는 필드만 비교한다 — 주지 않은 필드는 그대로 둔다.
 * 입력에 없는 문서 요구사항은 "빠짐"이다. 적용은 기본으로 dropped로 바꾸고, acceptRemovals면 지운다.
 *
 * <p>적용은 이 계획이 정한 code를 채운 입력 목록을 {@link DocumentEditor#applyRequirements}에 그대로 넘긴다.
 * 그래서 검증과 개정 번호 규칙(제목·내용이 바뀌면 반영 대기)이 save_requirements와 같다.
 */
public final class RequirementSync {

    public static final String FIELD_TITLE = "title";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_DOMAIN = "domain";
    public static final String FIELD_TABLES = "tables";
    public static final String FIELD_CRITERIA = "criteria";

    /** 필드 하나의 차이 — 값은 화면에 보여 줄 문자열이다(tables는 쉼표로 이은 물리명) */
    public record FieldChange(String field, String before, String after) {
    }

    /** 추가할 요구사항 — code는 입력이 정한 새 코드이거나 null(다음 번호) */
    public record Added(int index, String code, String title) {
    }

    /** 고칠 요구사항 — matchedBy는 code 또는 title */
    public record Updated(int index, String code, String title, String matchedBy, List<FieldChange> changes) {
    }

    /** 입력에 없는 문서 요구사항 — action은 drop(dropped로 바꾼다)·remove(지운다)·none(이미 dropped) */
    public record Missing(String code, String title, String status, String action) {
    }

    public record Plan(List<Added> added, List<Updated> updated, List<Missing> missing, int unchanged,
                       List<RequirementItem> resolvedItems) {

        public int changeCount() {
            int missingChanges = (int) missing.stream().filter(item -> !"none".equals(item.action())).count();
            return added.size() + updated.size() + missingChanges;
        }

        /** 계획 지문 — acceptRemovals와 무관하다(빠짐 목록은 그대로고 적용 방식만 다르다) */
        public String fingerprint() {
            StringBuilder canonical = new StringBuilder();
            for (Added item : added) {
                canonical.append("A|").append(item.index()).append('|').append(item.code()).append('|').append(item.title()).append('\n');
            }
            for (Updated item : updated) {
                canonical.append("U|").append(item.index()).append('|').append(item.code());
                for (FieldChange change : item.changes()) {
                    canonical.append('|').append(change.field()).append('=').append(change.before()).append('>').append(change.after());
                }
                canonical.append('\n');
            }
            for (Missing item : missing) {
                canonical.append("M|").append(item.code()).append('|').append(item.status()).append('\n');
            }
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 빠진 요구사항 가운데 dropped로 바꿀 것 */
        public List<String> toDrop() {
            return missing.stream().filter(item -> "drop".equals(item.action())).map(Missing::code).toList();
        }

        /** 빠진 요구사항 가운데 지울 것 */
        public List<String> toRemove() {
            return missing.stream().filter(item -> "remove".equals(item.action())).map(Missing::code).toList();
        }
    }

    private RequirementSync() {
    }

    /**
     * @param root 문서 content(읽기만 한다)
     * @param items 기준 목록 — 검증은 적용(DocumentEditor)이 한다. 여기서는 맞추기와 차이만 계산한다
     */
    public static Plan plan(JsonNode root, List<RequirementItem> items, boolean acceptRemovals) {
        JsonNode requirements = root.path("diagram").path("requirements");
        Map<String, String> tableNames = new HashMap<>();
        root.path("model").path("tables").forEach(table ->
                tableNames.put(table.path("id").asText(), table.path("physicalName").asText()));
        Map<String, String> areaNames = new HashMap<>();
        root.path("diagram").path("areas").forEach(area -> areaNames.put(area.path("id").asText(), area.path("name").asText()));

        Map<String, JsonNode> byCode = new LinkedHashMap<>();
        requirements.forEach(node -> byCode.put(node.path("code").asText(), node));
        Set<String> matched = new HashSet<>();
        List<Added> added = new ArrayList<>();
        List<Updated> updated = new ArrayList<>();
        List<RequirementItem> resolved = new ArrayList<>();
        int unchanged = 0;

        // 1) code로 맞춘다 — 제목 맞추기가 code로 맞춘 요구사항을 가로채지 않게 먼저 한다
        String[] codeOf = new String[items.size()];
        String[] matchedBy = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            RequirementItem item = items.get(i);
            if (item != null && item.code() != null && byCode.containsKey(item.code())) {
                codeOf[i] = item.code();
                matchedBy[i] = "code";
                matched.add(item.code());
            }
        }
        // 2) code가 없는 항목은 제목으로 — 아직 맞추지 않은 요구사항 가운데 문서 순서로 첫 번째
        for (int i = 0; i < items.size(); i++) {
            RequirementItem item = items.get(i);
            if (item == null || item.code() != null || item.title() == null) {
                continue;
            }
            String key = titleKey(item.title());
            for (JsonNode node : requirements) {
                String code = node.path("code").asText();
                if (!matched.contains(code) && titleKey(node.path("title").asText()).equals(key)) {
                    codeOf[i] = code;
                    matchedBy[i] = "title";
                    matched.add(code);
                    break;
                }
            }
        }

        for (int i = 0; i < items.size(); i++) {
            RequirementItem item = items.get(i);
            if (item == null) {
                resolved.add(null);
                continue;
            }
            if (codeOf[i] == null) {
                added.add(new Added(i, item.code(), item.title()));
                resolved.add(new RequirementItem(item.code(), item.title(),
                        item.description() == null ? null : normalize(item.description()), item.status(),
                        item.scope(), item.domain(), item.tables(), item.criteria()));
                continue;
            }
            JsonNode node = byCode.get(codeOf[i]);
            List<FieldChange> changes = new ArrayList<>();
            // 제목으로 맞췄으면 제목은 같은 것이다 — 공백·대소문자 차이로 고치지 않는다
            String title = item.title() == null || "title".equals(matchedBy[i]) ? null : item.title().strip();
            compare(changes, FIELD_TITLE, node.path("title").asText(""), title);
            compare(changes, FIELD_DESCRIPTION, normalize(node.path("description").asText("")),
                    item.description() == null ? null : normalize(item.description()));
            String status = item.status();
            // 다시 나타난 요구사항 — 상태를 주지 않았으면 검토 중(draft)으로 되살린다
            if (status == null && "dropped".equals(node.path("status").asText())) {
                status = "draft";
            }
            compare(changes, FIELD_STATUS, node.path("status").asText("draft"), status);
            String areaId = node.path("areaId").isTextual() ? node.path("areaId").asText() : null;
            compare(changes, FIELD_DOMAIN, areaId == null ? "" : areaNames.getOrDefault(areaId, ""),
                    item.domain() == null ? null : item.domain().strip());
            if (item.tables() != null) {
                List<String> before = new ArrayList<>();
                node.path("tableIds").forEach(id -> before.add(tableNames.getOrDefault(id.asText(), id.asText())));
                compare(changes, FIELD_TABLES, joinSorted(before), joinSorted(item.tables()));
            }
            if (item.criteria() != null) {
                // 수용 기준은 문구와 확인 SQL·기대값을 줄마다 비교한다(체크 여부는 문서 쪽 값이다)
                List<String> before = new ArrayList<>();
                node.path("criteria").forEach(criterion -> before.add(criterionKey(criterion.path("text").asText(""),
                        criterion.path("check").path("sql").asText(""), criterion.path("check").path("expect").asText(""))));
                List<String> after = new ArrayList<>();
                item.criteria().forEach(criterion -> after.add(criterion == null ? "" : criterionKey(criterion.text(),
                        criterion.sql(), criterion.sql() == null || criterion.sql().isBlank() ? "" : criterion.expect())));
                compare(changes, FIELD_CRITERIA, String.join("\n", before), String.join("\n", after));
            }
            RequirementItem withCode = new RequirementItem(codeOf[i], title,
                    item.description() == null ? null : normalize(item.description()), status,
                    item.scope(), item.domain(), item.tables(), item.criteria());
            resolved.add(withCode);
            if (changes.isEmpty()) {
                unchanged++;
            } else {
                updated.add(new Updated(i, codeOf[i], node.path("title").asText(""), matchedBy[i], changes));
            }
        }

        List<Missing> missing = new ArrayList<>();
        for (JsonNode node : requirements) {
            String code = node.path("code").asText();
            if (matched.contains(code)) {
                continue;
            }
            String status = node.path("status").asText("draft");
            String action = acceptRemovals ? "remove" : ("dropped".equals(status) ? "none" : "drop");
            missing.add(new Missing(code, node.path("title").asText(""), status, action));
        }
        return new Plan(List.copyOf(added), List.copyOf(updated), List.copyOf(missing), unchanged, resolved);
    }

    private static void compare(List<FieldChange> changes, String field, String before, String after) {
        if (after != null && !Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before, after));
        }
    }

    private static String criterionKey(String text, String sql, String expect) {
        String check = sql == null || sql.isBlank() ? "" : " [" + sql.strip() + " = "
                + (expect == null || expect.isBlank() ? "0" : expect.strip()) + "]";
        return (text == null ? "" : text.strip()) + check;
    }

    private static String titleKey(String title) {
        return title.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").stripTrailing();
    }

    private static String joinSorted(List<String> names) {
        return names.stream().map(name -> name.strip().toLowerCase(Locale.ROOT)).sorted().distinct()
                .reduce((a, b) -> a + ", " + b).orElse("");
    }
}
