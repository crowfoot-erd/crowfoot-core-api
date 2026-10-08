package net.java21.crowfoot.api.model.edit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.java21.crowfoot.api.model.edit.EditRequests.AreaItem;
import net.java21.crowfoot.api.model.edit.EditRequests.CheckItem;
import net.java21.crowfoot.api.model.edit.EditRequests.CheckRef;
import net.java21.crowfoot.api.model.edit.EditRequests.IndexRef;
import net.java21.crowfoot.api.model.edit.EditRequests.ColumnItem;
import net.java21.crowfoot.api.model.edit.EditRequests.ColumnMappingItem;
import net.java21.crowfoot.api.model.edit.EditRequests.ColumnRef;
import net.java21.crowfoot.api.model.edit.EditRequests.DomainTypeRef;
import net.java21.crowfoot.api.model.edit.EditRequests.IndexColumnItem;
import net.java21.crowfoot.api.model.edit.EditRequests.IndexItem;
import net.java21.crowfoot.api.model.edit.EditRequests.RelationshipItem;
import net.java21.crowfoot.api.model.edit.EditRequests.RelationshipRef;
import net.java21.crowfoot.api.model.edit.EditRequests.RequirementItem;
import net.java21.crowfoot.api.model.edit.EditRequests.TableItem;
import net.java21.crowfoot.api.model.edit.EditRequests.UniqueItem;
import net.java21.crowfoot.common.ErrorResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 문서 본체를 요구사항·테이블·관계 단위로 고친다 (08-core/17-model-edit.md).
 *
 * <p>본체 JSON 트리를 직접 고친다 — 이 클래스가 모르는 키는 그대로 남는다.
 * 본체를 만드는 규칙(키 이름, 외래 키 컬럼, 외래 키 인덱스, 컬럼 순서)은 에디터 코드
 * (crowfoot-web/src/features/editor/model의 changes.ts·relationship.ts·keys.ts)가 원천이다.
 * 같은 규칙을 Java로 옮긴 것이므로 에디터의 규칙을 바꾸면 여기도 함께 바꾼다 —
 * 두 구현은 docs의 시험 자료(assets/model-edit-fixtures)로 맞춘다(Section 6).
 *
 * <p>입력은 이름으로 가리킨다(테이블·컬럼 물리명, 요구사항 코드, 그룹 이름). 입력이 틀리면 사유를 모아
 * {@link EditValidationException}으로 던진다. 위치(diagram.nodes)는 만들지 않는다 — 에디터가 열 때 배치한다.
 */
public final class DocumentEditor {

    static final Pattern PHYSICAL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    static final Pattern REQUIREMENT_CODE = Pattern.compile("^REQ-(\\d{3,})$");
    static final String SEPARATOR = "-----";
    static final int REQUIREMENT_LIMIT = 500;
    /** 요구사항 하나의 수용 기준 상한 — 에디터 화면과 같다(05-editor/02-ui.md Section 21) */
    static final int CRITERIA_LIMIT = 20;
    /** 공용 타입 코드 — DDL 생성기와 같은 카탈로그(05-editor/01-core.md §17) */
    static final Set<String> DATA_TYPES = net.java21.crowfoot.api.model.ddl.DbmsTemplates.COMMON_TYPES;
    static final Set<String> LENGTH_TYPES = Set.of("CHAR", "VARCHAR", "BINARY", "VARBINARY");

    /** 소수 초 자릿수(0~6)를 precision에 두는 타입 */
    static final Set<String> FRACTIONAL_TYPES = Set.of("TIME", "DATETIME", "TIMESTAMP");

    static final Set<String> INDEX_TYPES = Set.of("BTREE", "FULLTEXT", "SPATIAL", "HASH", "GIN", "GIST", "BRIN", "SPGIST");

    /** 연산자 클래스 이름 — 스키마 한정 가능(public.gin_trgm_ops) */
    private static final Pattern OPCLASS = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");
    static final Set<String> PRECISION_TYPES = Set.of("DECIMAL", "NUMERIC");
    static final Set<String> INTEGER_TYPES = Set.of("INT", "BIGINT", "SMALLINT", "TINYINT");
    static final Set<String> STATUSES = Set.of("draft", "confirmed", "dropped");
    static final Set<String> SCOPES = Set.of("tables", "document");
    static final Set<String> RELATIONSHIP_TYPES = Set.of("ONE_TO_ONE", "ONE_TO_MANY");
    static final Set<String> PARENT_MULTIPLICITIES = Set.of("EXACTLY_ONE", "ZERO_OR_ONE");
    static final Map<String, Set<String>> CHILD_MULTIPLICITIES = Map.of(
            "ONE_TO_ONE", Set.of("EXACTLY_ONE", "ZERO_OR_ONE"),
            "ONE_TO_MANY", Set.of("ZERO_OR_MORE", "ONE_OR_MORE"));
    static final Set<String> REFERENTIAL_ACTIONS = Set.of("NO_ACTION", "RESTRICT", "CASCADE", "SET_NULL", "SET_DEFAULT");
    static final Set<String> COLORS = Set.of("default", "red", "orange", "amber", "yellow", "green", "teal", "sky", "blue", "violet", "pink");
    static final Set<String> IDENTITY_GENERATIONS = Set.of("ALWAYS", "BY_DEFAULT");
    static final List<String> DOMAIN_FIELDS = List.of("dataType", "length", "precision", "scale", "nullable", "defaultValue");

    /** 변경 요약 한 줄 — 응답의 summary와 버전 기록의 changeSummary 항목이 된다 */
    public record Change(String kind, String action, String table, String name) {
    }

    /** 저장은 했지만 알려야 하는 것 (Section 5.2) */
    public record Warning(String code, String target, String message) {
    }

    private final ObjectNode root;
    private final ArrayNode tables;
    private final ArrayNode relationships;
    private final ObjectNode diagram;
    private final ArrayNode areas;
    private final ArrayNode requirements;
    private final String databaseType;
    private final Map<String, DomainTypeRef> domainTypes;
    private final Supplier<String> ids;

    private final List<ErrorResponse.FieldError> errors = new ArrayList<>();
    private final List<Change> changes = new ArrayList<>();
    private final List<Warning> warnings = new ArrayList<>();

    /**
     * @param root         본체 JSON(고칠 사본) — {@link #readable(JsonNode)}를 통과한 것
     * @param databaseType 문서의 대상 DBMS 코드 — 외래 키 인덱스를 만들지 정한다
     * @param domainTypes  워크스페이스 도메인 타입(이름 소문자 → 값)
     */
    public DocumentEditor(ObjectNode root, String databaseType, Map<String, DomainTypeRef> domainTypes) {
        this(root, databaseType, domainTypes, () -> UUID.randomUUID().toString());
    }

    /** 테스트용 — id를 정해진 순서로 만들어 기대 본체와 그대로 견준다 */
    DocumentEditor(ObjectNode root, String databaseType, Map<String, DomainTypeRef> domainTypes, Supplier<String> ids) {
        this.root = root;
        ObjectNode model = (ObjectNode) root.get("model");
        this.tables = (ArrayNode) model.get("tables");
        this.relationships = (ArrayNode) model.get("relationships");
        this.diagram = (ObjectNode) root.get("diagram");
        this.areas = diagram.get("areas") instanceof ArrayNode array ? array : diagram.putArray("areas");
        this.requirements = diagram.get("requirements") instanceof ArrayNode array ? array : diagram.putArray("requirements");
        // 검증 예외(v1.34) — 편집 API는 다루지 않지만, 에디터가 여는 본체와 같게 빈 목록을 둔다
        if (!(diagram.get("validationExceptions") instanceof ArrayNode)) {
            diagram.putArray("validationExceptions");
        }
        this.databaseType = databaseType == null ? "" : databaseType;
        this.domainTypes = domainTypes;
        this.ids = ids;
    }

    /** 편집 API가 고칠 수 있는 본체인가 — 구조가 어긋난 문서는 고치지 않는다(409 CONTENT_UNREADABLE) */
    public static boolean readable(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("model").isObject() || !root.path("diagram").isObject()) {
            return false;
        }
        JsonNode model = root.path("model");
        if (!model.path("tables").isArray() || !model.path("relationships").isArray()) {
            return false;
        }
        for (JsonNode table : model.path("tables")) {
            if (!table.isObject() || !table.path("id").isTextual() || !table.path("physicalName").isTextual()
                    || !table.path("columns").isArray()) {
                return false;
            }
            for (JsonNode column : table.path("columns")) {
                if (!column.isObject() || !column.path("id").isTextual() || !column.path("physicalName").isTextual()) {
                    return false;
                }
            }
        }
        for (JsonNode relationship : model.path("relationships")) {
            if (!relationship.isObject() || !relationship.path("parentTableId").isTextual()
                    || !relationship.path("childTableId").isTextual() || !relationship.path("columnMappings").isArray()) {
                return false;
            }
        }
        JsonNode diagram = root.path("diagram");
        return isArrayOrAbsent(diagram, "areas") && isArrayOrAbsent(diagram, "requirements");
    }

    private static boolean isArrayOrAbsent(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isArray();
    }

    public List<Change> changes() {
        return changes;
    }

    public List<Warning> warnings() {
        return warnings;
    }

    /** 모은 사유가 있으면 던진다 — 반영을 다 돈 뒤에 부른다 */
    public void throwIfInvalid() {
        if (!errors.isEmpty()) {
            throw new EditValidationException(errors);
        }
    }

    private void error(String field, String message) {
        errors.add(new ErrorResponse.FieldError(field, "INVALID", message));
    }

    /* =====================================================================
     * 요구사항 반영 (Section 3.2)
     * ===================================================================== */

    public void applyRequirements(List<RequirementItem> items) {
        if (items == null || items.isEmpty()) {
            error("items", "요구사항 항목이 하나 이상 있어야 합니다");
            return;
        }
        if (items.size() > 200) {
            error("items", "한 번에 200개까지 보낼 수 있습니다");
            return;
        }
        Set<String> seen = new HashSet<>();
        int nextNumber = maxRequirementNumber() + 1;
        // 요청이 직접 정한 새 코드의 번호를 먼저 본다 — 자동으로 붙이는 번호가 그것과 겹치지 않게 한다
        for (RequirementItem item : items) {
            if (item != null && item.code() != null) {
                Matcher matcher = REQUIREMENT_CODE.matcher(item.code());
                if (matcher.matches()) {
                    nextNumber = Math.max(nextNumber, parseNumber(matcher.group(1)) + 1);
                }
            }
        }
        for (int i = 0; i < items.size(); i++) {
            RequirementItem item = items.get(i);
            String at = "items[" + i + "]";
            if (item == null) {
                error(at, "항목이 비어 있습니다");
                continue;
            }
            String code = item.code();
            if (code != null) {
                if (!REQUIREMENT_CODE.matcher(code).matches()) {
                    error(at + ".code", "코드는 REQ-001 형식이어야 합니다: " + code);
                    continue;
                }
                if (!seen.add(code)) {
                    error(at + ".code", "같은 코드가 요청에 두 번 있습니다: " + code);
                    continue;
                }
            }
            ObjectNode existing = code == null ? null : requirement(code);
            if (existing == null) {
                if (code == null) {
                    code = String.format("REQ-%03d", nextNumber++);
                }
                createRequirement(at, code, item);
            } else {
                updateRequirement(at, existing, item);
            }
        }
        if (requirements.size() > REQUIREMENT_LIMIT) {
            throw new RequirementLimitException();
        }
    }

    /** 요구사항 500개 상한 (409 REQUIREMENT_LIMIT_EXCEEDED) */
    public static class RequirementLimitException extends RuntimeException {
    }

    private void createRequirement(String at, String code, RequirementItem item) {
        String title = item.title() == null ? "" : item.title().strip();
        if (title.isEmpty() || title.length() > 100) {
            error(at + ".title", "새 요구사항에는 1~100자의 제목이 있어야 합니다");
            return;
        }
        String description = item.description() == null ? "" : item.description();
        if (description.length() > 2000) {
            error(at + ".description", "내용은 2,000자 이하여야 합니다");
            return;
        }
        String status = item.status() == null ? "draft" : item.status();
        if (!STATUSES.contains(status)) {
            error(at + ".status", "상태는 draft, confirmed, dropped 가운데 하나여야 합니다: " + status);
            return;
        }
        String scope = item.scope() == null ? "tables" : item.scope();
        if (!SCOPES.contains(scope)) {
            error(at + ".scope", "범위는 tables, document 가운데 하나여야 합니다: " + scope);
            return;
        }
        if (!checkDocumentScope(at, scope, item)) {
            return;
        }
        List<String> tableIds = item.tables() == null ? List.of() : resolveTableIds(at + ".tables", item.tables());
        if (tableIds == null) {
            return;
        }
        ObjectNode node = requirements.addObject();
        node.put("id", ids.get());
        node.put("code", code);
        String areaId = item.domain() == null || item.domain().isBlank() ? null : areaIdFor(item.domain().strip());
        if (areaId == null) {
            node.putNull("areaId");
        } else {
            node.put("areaId", areaId);
        }
        node.put("scope", scope);
        node.put("title", title);
        node.put("description", description);
        node.put("status", status);
        node.put("revision", 1);
        // 연결을 직접 정했으면 반영을 확인한 것으로 본다
        node.put("appliedRevision", item.tables() != null ? 1 : 0);
        ArrayNode ids = node.putArray("tableIds");
        tableIds.forEach(ids::add);
        if (item.criteria() != null) {
            applyCriteria(at, node, item.criteria());
        }
        changes.add(new Change("requirement", "add", "", code));
        placeInRequirementArea(node, tableIds);
    }

    /**
     * 수용 기준을 목록째 바꾼다(v1.36 — 08-core/17-model-edit.md Section 2.2). 문구가 같은 기준은 id와 체크(done)를 이어받는다.
     * sql이 있으면 데이터 확인(check)을 둔다. 개정 번호는 오르지 않는다(반영 대기가 되지 않는다). 바뀌었으면 true
     */
    private boolean applyCriteria(String at, ObjectNode node, List<EditRequests.CriterionItem> items) {
        if (items.size() > CRITERIA_LIMIT) {
            error(at + ".criteria", "수용 기준은 " + CRITERIA_LIMIT + "개까지입니다");
            return false;
        }
        Map<String, JsonNode> previous = new HashMap<>();
        node.path("criteria").forEach(criterion -> previous.putIfAbsent(criterion.path("text").asText("").strip(), criterion));
        ArrayNode next = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < items.size(); i++) {
            EditRequests.CriterionItem item = items.get(i);
            String where = at + ".criteria[" + i + "]";
            String text = item == null || item.text() == null ? "" : item.text().strip();
            if (text.isEmpty() || text.length() > 200) {
                error(where + ".text", "수용 기준은 1~200자여야 합니다");
                return false;
            }
            String sql = item.sql() == null ? "" : item.sql().strip();
            if (sql.length() > 4000) {
                error(where + ".sql", "확인 SQL은 4,000자 이하여야 합니다");
                return false;
            }
            String expect = item.expect() == null || item.expect().isBlank() ? "0" : item.expect().strip();
            if (expect.length() > 200) {
                error(where + ".expect", "기대값은 200자 이하여야 합니다");
                return false;
            }
            JsonNode before = previous.remove(text);
            ObjectNode criterion = next.addObject();
            criterion.put("id", before != null ? before.path("id").asText() : ids.get());
            criterion.put("text", text);
            criterion.put("done", before != null && before.path("done").asBoolean(false));
            if (!sql.isEmpty()) {
                ObjectNode check = criterion.putObject("check");
                check.put("sql", sql);
                check.put("expect", expect);
            }
        }
        if (next.equals(node.path("criteria")) || (next.isEmpty() && !node.has("criteria"))) {
            return false;
        }
        if (next.isEmpty()) {
            node.remove("criteria");
        } else {
            node.set("criteria", next);
        }
        return true;
    }

    private void updateRequirement(String at, ObjectNode node, RequirementItem item) {
        String scope = node.path("scope").asText("tables");
        if (item.scope() != null && !item.scope().equals(scope)) {
            error(at + ".scope", "범위는 고칠 수 없습니다(" + scope + ")");
            return;
        }
        if (!checkDocumentScope(at, scope, item)) {
            return;
        }
        boolean changed = false;
        boolean contentChanged = false;
        if (item.title() != null) {
            String title = item.title().strip();
            if (title.isEmpty() || title.length() > 100) {
                error(at + ".title", "제목은 1~100자여야 합니다");
                return;
            }
            if (!title.equals(node.path("title").asText(""))) {
                node.put("title", title);
                contentChanged = true;
            }
        }
        if (item.description() != null) {
            if (item.description().length() > 2000) {
                error(at + ".description", "내용은 2,000자 이하여야 합니다");
                return;
            }
            if (!item.description().equals(node.path("description").asText(""))) {
                node.put("description", item.description());
                contentChanged = true;
            }
        }
        if (item.status() != null) {
            if (!STATUSES.contains(item.status())) {
                error(at + ".status", "상태는 draft, confirmed, dropped 가운데 하나여야 합니다: " + item.status());
                return;
            }
            if (!item.status().equals(node.path("status").asText(""))) {
                node.put("status", item.status());
                changed = true;
            }
        }
        if (item.domain() != null) {
            String areaId = item.domain().isBlank() ? null : areaIdFor(item.domain().strip());
            String before = node.path("areaId").isTextual() ? node.path("areaId").asText() : null;
            if (!java.util.Objects.equals(before, areaId)) {
                if (areaId == null) {
                    node.putNull("areaId");
                } else {
                    node.put("areaId", areaId);
                }
                changed = true;
            }
        }
        if (contentChanged) {
            // 내용이 바뀌면 개정 번호가 오른다 — 그 요구사항은 반영 대기가 된다
            node.put("revision", node.path("revision").asInt(1) + 1);
            changed = true;
        }
        if (item.tables() != null) {
            List<String> tableIds = resolveTableIds(at + ".tables", item.tables());
            if (tableIds == null) {
                return;
            }
            int revision = node.path("revision").asInt(1);
            if (!tableIds.equals(strings(node.path("tableIds"))) || node.path("appliedRevision").asInt(0) != revision) {
                ArrayNode ids = node.putArray("tableIds");
                tableIds.forEach(ids::add);
                node.put("appliedRevision", revision);
                changed = true;
            }
        }
        if (item.criteria() != null && applyCriteria(at, node, item.criteria())) {
            changed = true;
        }
        if (changed) {
            changes.add(new Change("requirement", "update", "", node.path("code").asText()));
            placeInRequirementArea(node, strings(node.path("tableIds")));
        }
    }

    /** 공통 요구사항(scope=document)에는 도메인과 테이블 연결을 줄 수 없다 */
    private boolean checkDocumentScope(String at, String scope, RequirementItem item) {
        if (!"document".equals(scope)) {
            return true;
        }
        if (item.domain() != null && !item.domain().isBlank()) {
            error(at + ".domain", "공통 요구사항(scope=document)에는 도메인을 줄 수 없습니다");
            return false;
        }
        if (item.tables() != null && !item.tables().isEmpty()) {
            error(at + ".tables", "공통 요구사항(scope=document)에는 테이블을 연결할 수 없습니다");
            return false;
        }
        return true;
    }

    private int maxRequirementNumber() {
        int max = 0;
        for (JsonNode node : requirements) {
            Matcher matcher = REQUIREMENT_CODE.matcher(node.path("code").asText(""));
            if (matcher.matches()) {
                max = Math.max(max, parseNumber(matcher.group(1)));
            }
        }
        return max;
    }

    private static int parseNumber(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ObjectNode requirement(String code) {
        for (JsonNode node : requirements) {
            if (code.equals(node.path("code").asText(""))) {
                return (ObjectNode) node;
            }
        }
        return null;
    }

    /** 그룹 이름 → id. 그 이름의 그룹이 없으면 빈 그룹을 만든다 */
    private String areaIdFor(String name) {
        ObjectNode area = area(name);
        if (area == null) {
            area = newArea(name);
            changes.add(new Change("area", "add", "", name));
        }
        return area.path("id").asText();
    }

    private ObjectNode area(String name) {
        for (JsonNode node : areas) {
            if (name.equals(node.path("name").asText(""))) {
                return (ObjectNode) node;
            }
        }
        return null;
    }

    private ObjectNode newArea(String name) {
        ObjectNode area = areas.addObject();
        area.put("id", ids.get());
        area.put("name", name);
        area.put("description", "");
        area.put("color", "default");
        area.putArray("tableIds");
        return area;
    }

    /** 테이블 물리명 목록 → id 목록. 없는 이름이 있으면 사유를 남기고 null */
    private List<String> resolveTableIds(String at, List<String> names) {
        List<String> out = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < names.size(); i++) {
            ObjectNode table = table(names.get(i));
            if (table == null) {
                error(at + "[" + i + "]", "테이블이 없습니다: " + names.get(i));
                ok = false;
            } else if (!out.contains(table.path("id").asText())) {
                out.add(table.path("id").asText());
            }
        }
        return ok ? out : null;
    }

    /* =====================================================================
     * 스키마 반영 (Section 3.3) — 테이블 → 관계 → 그룹 순서
     * ===================================================================== */

    public void applySchema(List<TableItem> tableItems, List<RelationshipItem> relationshipItems, List<AreaItem> areaItems) {
        boolean empty = isEmpty(tableItems) && isEmpty(relationshipItems) && isEmpty(areaItems);
        if (empty) {
            error("tables", "tables, relationships, areas 가운데 하나는 있어야 합니다");
            return;
        }
        if (tableItems != null && tableItems.size() > 100) {
            error("tables", "한 번에 테이블 100개까지 보낼 수 있습니다");
            return;
        }
        if (tableItems != null) {
            for (int i = 0; i < tableItems.size(); i++) {
                applyTable("tables[" + i + "]", tableItems.get(i));
            }
        }
        if (relationshipItems != null) {
            for (int i = 0; i < relationshipItems.size(); i++) {
                applyRelationship("relationships[" + i + "]", relationshipItems.get(i));
            }
        }
        if (areaItems != null) {
            for (int i = 0; i < areaItems.size(); i++) {
                applyArea("areas[" + i + "]", areaItems.get(i));
            }
        }
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    /* ---------- 테이블 ---------- */

    private void applyTable(String at, TableItem item) {
        if (item == null || item.physicalName() == null) {
            error(at + ".physicalName", "테이블 물리명이 있어야 합니다");
            return;
        }
        if (!PHYSICAL_NAME.matcher(item.physicalName()).matches()) {
            error(at + ".physicalName", "물리명은 소문자로 시작하고 소문자·숫자·밑줄만 씁니다(63자 이하): " + item.physicalName());
            return;
        }
        ObjectNode table = table(item.physicalName());
        boolean created = table == null;
        String before = created ? null : table.toString();
        int changesBefore = changes.size();
        if (created) {
            if (item.rename() != null) {
                error(at + ".rename", "rename은 이미 있는 테이블에만 씁니다");
                return;
            }
            table = tables.addObject();
            table.put("id", ids.get());
            table.put("logicalName", "");
            table.put("physicalName", item.physicalName());
            table.putNull("comment");
            table.putArray("columns");
            table.putNull("primaryKey");
            table.putArray("uniques");
            table.putArray("indexes");
            table.putArray("checks");
        } else if (item.rename() != null && !item.rename().equalsIgnoreCase(table.path("physicalName").asText())) {
            if (!PHYSICAL_NAME.matcher(item.rename()).matches()) {
                error(at + ".rename", "물리명은 소문자로 시작하고 소문자·숫자·밑줄만 씁니다(63자 이하): " + item.rename());
                return;
            }
            if (table(item.rename()) != null) {
                error(at + ".rename", "같은 물리명의 테이블이 이미 있습니다: " + item.rename());
                return;
            }
            followRename(table, table.path("physicalName").asText(), item.rename(), item.logicalName());
            table.put("physicalName", item.rename());
        }
        String tableName = table.path("physicalName").asText();
        if (!applyNames(at, table, item.logicalName(), item.description())) {
            return;
        }
        if (item.columns() != null) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < item.columns().size(); i++) {
                ColumnItem column = item.columns().get(i);
                String columnAt = at + ".columns[" + i + "]";
                if (column == null || column.physicalName() == null) {
                    error(columnAt + ".physicalName", "컬럼 물리명이 있어야 합니다");
                    continue;
                }
                if (!seen.add(column.physicalName().toLowerCase(Locale.ROOT))) {
                    error(columnAt + ".physicalName", "같은 컬럼이 요청에 두 번 있습니다: " + column.physicalName());
                    continue;
                }
                applyColumn(columnAt, table, column);
            }
        }
        if (item.primaryKey() != null) {
            applyPrimaryKey(at + ".primaryKey", table, item.primaryKey());
        }
        checkAutoIncrement(at, table);
        if (item.uniques() != null) {
            for (int i = 0; i < item.uniques().size(); i++) {
                applyUnique(at + ".uniques[" + i + "]", table, item.uniques().get(i));
            }
        }
        if (item.indexes() != null) {
            for (int i = 0; i < item.indexes().size(); i++) {
                applyIndex(at + ".indexes[" + i + "]", table, item.indexes().get(i));
            }
        }
        if (item.checks() != null) {
            for (int i = 0; i < item.checks().size(); i++) {
                applyCheck(at + ".checks[" + i + "]", table, item.checks().get(i));
            }
        }
        boolean linked = false;
        if (item.requirementCodes() != null) {
            for (int i = 0; i < item.requirementCodes().size(); i++) {
                linked |= linkRequirement(at + ".requirementCodes[" + i + "]", table, item.requirementCodes().get(i));
            }
        }
        if (created) {
            // 테이블 추가는 한 줄로 요약한다 — 그 테이블의 컬럼·키 추가는 따로 적지 않는다(요구사항 연결은 남긴다)
            List<Change> own = new ArrayList<>(changes.subList(changesBefore, changes.size()));
            changes.subList(changesBefore, changes.size()).clear();
            own.stream().filter(change -> "requirement".equals(change.kind()) || "area".equals(change.kind())).forEach(changes::add);
            changes.add(new Change("table", "add", tableName, tableName));
            if (!linked && !isTraced(table.path("id").asText())) {
                warnings.add(new Warning("UNTRACED_TABLE", tableName, "근거 요구사항이 없는 테이블입니다: " + tableName));
            }
        } else if (!table.toString().equals(before) && changes.stream().skip(changesBefore).noneMatch(change -> "column".equals(change.kind()))) {
            changes.add(new Change("table", "update", tableName, tableName));
        }
    }

    /** 논리명과 설명 — 저장할 때는 `논리명-----설명` 한 문자열이다. 준 것만 바꾼다 */
    private boolean applyNames(String at, ObjectNode node, String logicalName, String description) {
        if (logicalName == null && description == null) {
            return true;
        }
        if (logicalName != null && (logicalName.length() > 100 || logicalName.contains(SEPARATOR))) {
            error(at + ".logicalName", "논리명은 100자 이하이고 '-----'를 포함할 수 없습니다");
            return false;
        }
        if (description != null && (description.length() > 500 || description.contains(SEPARATOR))) {
            error(at + ".description", "설명은 500자 이하이고 '-----'를 포함할 수 없습니다");
            return false;
        }
        String stored = node.path("logicalName").asText("");
        int cut = stored.indexOf(SEPARATOR);
        String logical = logicalName != null ? logicalName : (cut < 0 ? stored : stored.substring(0, cut));
        String desc = description != null ? description : (cut < 0 ? "" : stored.substring(cut + SEPARATOR.length()));
        node.put("logicalName", desc.isEmpty() ? logical : logical + SEPARATOR + desc);
        return true;
    }

    /* ---------- 컬럼 ---------- */

    private void applyColumn(String at, ObjectNode table, ColumnItem item) {
        if (!PHYSICAL_NAME.matcher(item.physicalName()).matches()) {
            error(at + ".physicalName", "물리명은 소문자로 시작하고 소문자·숫자·밑줄만 씁니다(63자 이하): " + item.physicalName());
            return;
        }
        String tableName = table.path("physicalName").asText();
        ArrayNode columns = (ArrayNode) table.get("columns");
        ObjectNode column = column(table, item.physicalName());
        boolean created = column == null;
        String before = created ? null : column.toString();
        DomainTypeRef domain = null;
        if (item.domainType() != null && !item.domainType().isBlank()) {
            domain = domainTypes.get(item.domainType().strip().toLowerCase(Locale.ROOT));
            if (domain == null) {
                warnings.add(new Warning("DOMAIN_TYPE_NOT_FOUND", tableName + "." + item.physicalName(),
                        "도메인 타입이 워크스페이스에 없습니다: " + item.domainType()));
            }
        }
        if (created) {
            if (item.rename() != null) {
                error(at + ".rename", "rename은 이미 있는 컬럼에만 씁니다");
                return;
            }
            if (item.dataType() == null && domain == null) {
                error(at + ".dataType", "새 컬럼에는 dataType이나 워크스페이스에 있는 domainType이 있어야 합니다");
                return;
            }
            column = columnNode(item.physicalName());
        } else {
            if (isForeignKeyColumn(table, column.path("id").asText())
                    && (item.dataType() != null || item.length() != null || item.precision() != null || item.scale() != null
                    || item.nullable() != null || item.domainType() != null || Boolean.TRUE.equals(item.autoIncrement()))) {
                error(at, "외래 키 컬럼의 타입·길이·NULL 허용은 관계가 정합니다. 관계를 고치거나 부모 컬럼을 고치세요: " + item.physicalName());
                return;
            }
            if (item.rename() != null && !item.rename().equalsIgnoreCase(column.path("physicalName").asText())) {
                if (!PHYSICAL_NAME.matcher(item.rename()).matches()) {
                    error(at + ".rename", "물리명은 소문자로 시작하고 소문자·숫자·밑줄만 씁니다(63자 이하): " + item.rename());
                    return;
                }
                if (column(table, item.rename()) != null) {
                    error(at + ".rename", "같은 물리명의 컬럼이 이미 있습니다: " + item.rename());
                    return;
                }
                followRename(column, column.path("physicalName").asText(), item.rename(), item.logicalName());
                column.put("physicalName", item.rename());
            }
        }
        if (!applyNames(at, column, item.logicalName(), item.description())) {
            return;
        }
        // 도메인 타입 — 값을 도메인 타입의 것으로 채우고, 같이 준 값이 다르면 "다르게 씀"으로 기록한다
        if (domain != null) {
            column.put("dataType", domain.dataType());
            putInt(column, "length", domain.length());
            putInt(column, "precision", domain.precision());
            putInt(column, "scale", domain.scale());
            column.put("nullable", domain.nullable());
            putText(column, "defaultValue", domain.defaultValue());
        }
        ObjectNode valuesBefore = column.deepCopy();
        if (item.dataType() != null) {
            if (!DATA_TYPES.contains(item.dataType())) {
                error(at + ".dataType", "타입 코드가 아닙니다: " + item.dataType() + ". 공용 타입 코드(INT, BIGINT, VARCHAR, DECIMAL, DATETIME 등)를 씁니다");
                return;
            }
            if (!item.dataType().equals(column.path("dataType").asText(""))) {
                column.put("dataType", item.dataType());
                // 타입이 바뀌면 새 타입이 받지 않는 값은 비운다
                if (!LENGTH_TYPES.contains(item.dataType())) {
                    column.putNull("length");
                }
                if (!PRECISION_TYPES.contains(item.dataType()) && !FRACTIONAL_TYPES.contains(item.dataType())) {
                    column.putNull("precision");
                }
                if (!PRECISION_TYPES.contains(item.dataType())) {
                    column.putNull("scale");
                }
            }
        }
        String dataType = column.path("dataType").asText("");
        if (item.length() != null) {
            if (!LENGTH_TYPES.contains(dataType) || item.length() < 1) {
                error(at + ".length", "length는 CHAR·VARCHAR·BINARY·VARBINARY에만 넣고 1 이상이어야 합니다(" + dataType + ")");
                return;
            }
            column.put("length", item.length());
        }
        if (FRACTIONAL_TYPES.contains(dataType) && (item.precision() != null || item.scale() != null)) {
            if (item.scale() != null || item.precision() == null || item.precision() < 0 || item.precision() > 6) {
                error(at + ".precision", "TIME·DATETIME·TIMESTAMP의 precision은 소수 초 자릿수(0~6)이고 scale은 넣지 않습니다");
                return;
            }
            column.put("precision", item.precision());
        } else if (item.precision() != null || item.scale() != null) {
            if (!PRECISION_TYPES.contains(dataType)) {
                error(at + ".precision", "precision·scale은 DECIMAL·NUMERIC에만 넣습니다(" + dataType + ")");
                return;
            }
            if (item.precision() != null) {
                column.put("precision", item.precision());
            }
            if (item.scale() != null) {
                column.put("scale", item.scale());
            }
        }
        if (item.nullable() != null) {
            column.put("nullable", item.nullable());
        }
        if (item.defaultValue() != null) {
            if (item.defaultValue().length() > 255) {
                error(at + ".defaultValue", "기본값은 255자 이하여야 합니다");
                return;
            }
            putText(column, "defaultValue", item.defaultValue().isEmpty() ? null : item.defaultValue());
        }
        if (item.autoIncrement() != null) {
            column.put("autoIncrement", item.autoIncrement());
        }
        if (item.identityGeneration() != null) {
            // IDENTITY 종류(v1.37) — ALWAYS면 값을 직접 넣을 수 없다. 빈 값·BY_DEFAULT는 기본(BY DEFAULT)으로 돌린다
            String kind = item.identityGeneration().strip().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (!kind.isEmpty() && !IDENTITY_GENERATIONS.contains(kind)) {
                error(at + ".identityGeneration", "IDENTITY 종류는 ALWAYS 또는 BY_DEFAULT입니다: " + item.identityGeneration());
                return;
            }
            if ("ALWAYS".equals(kind)) {
                column.put("identityGeneration", "ALWAYS");
            } else {
                column.remove("identityGeneration");
            }
        }
        if (!column.path("autoIncrement").asBoolean(false)) {
            column.remove("identityGeneration");
        }
        if (item.onUpdate() != null) {
            if (item.onUpdate().length() > 255) {
                error(at + ".onUpdate", "onUpdate는 255자 이하여야 합니다");
                return;
            }
            putText(column, "onUpdate", item.onUpdate().isBlank() ? null : item.onUpdate().strip());
        }
        if (item.generated() != null) {
            String expression = item.generated().expression();
            if (expression == null || expression.isBlank()) {
                column.putNull("generated");
            } else if (expression.length() > 2000) {
                error(at + ".generated.expression", "생성식은 2000자 이하여야 합니다");
                return;
            } else {
                column.putObject("generated")
                        .put("expression", stripOuterParens(expression))
                        .put("stored", item.generated().stored() == null || item.generated().stored());
            }
        }
        if (column.path("generated").isObject()) {
            // 생성 컬럼에는 기본값·자동 증가·ON UPDATE가 없다(에디터의 컬럼 정보와 같다)
            column.putNull("defaultValue");
            column.put("autoIncrement", false);
            column.putNull("onUpdate");
        }
        // 도메인 타입 연결
        if (item.domainType() != null && item.domainType().isBlank()) {
            column.remove("domain");
        } else if (domain != null) {
            ObjectNode link = column.putObject("domain");
            link.put("id", domain.id());
            link.put("name", domain.name());
            link.put("version", domain.version());
            ArrayNode overrides = link.putArray("overrides");
            for (String field : DOMAIN_FIELDS) {
                if (!column.path(field).equals(valuesBefore.path(field))) {
                    overrides.add(field);
                }
            }
        } else if (column.path("domain").isObject()) {
            // 이미 연결된 컬럼의 값을 직접 고쳤다 — 고친 속성을 "다르게 씀"에 더한다(에디터의 patchColumn과 같다)
            ObjectNode link = (ObjectNode) column.get("domain");
            Set<String> overrides = new LinkedHashSet<>(strings(link.path("overrides")));
            for (String field : DOMAIN_FIELDS) {
                if (!column.path(field).equals(valuesBefore.path(field))) {
                    overrides.add(field);
                }
            }
            ArrayNode array = link.putArray("overrides");
            overrides.forEach(array::add);
        }
        // 기본 키 컬럼은 NOT NULL로 고정한다
        if (primaryKeyIds(table).contains(column.path("id").asText())) {
            column.put("nullable", false);
        }
        String name = column.path("physicalName").asText();
        if (created) {
            columns.add(column);
            changes.add(new Change("column", "add", tableName, name));
        } else if (!column.toString().equals(before)) {
            changes.add(new Change("column", "update", tableName, name));
        }
    }

    /** 이름 변경에 논리명을 맞춘다 — 논리명이 예전 물리명 그대로였으면(가져오기·리버스가 코멘트 없는 객체에 채운 값)
     *  새 물리명으로 바꾼다. 요청이 논리명을 따로 주면 그 값이 이긴다(뒤에서 applyNames가 덮어쓴다) */
    private static void followRename(ObjectNode node, String oldName, String newName, String requestedLogicalName) {
        if (requestedLogicalName != null) {
            return;
        }
        if (node.path("logicalName").asText("").equals(oldName)) {
            node.put("logicalName", newName);
        }
    }

    private ObjectNode columnNode(String physicalName) {
        ObjectNode column = root.objectNode();
        column.put("id", ids.get());
        column.put("logicalName", "");
        column.put("physicalName", physicalName);
        column.put("dataType", "VARCHAR");
        column.putNull("length");
        column.putNull("precision");
        column.putNull("scale");
        column.put("nullable", true);
        column.putNull("defaultValue");
        column.put("autoIncrement", false);
        column.putNull("comment");
        column.putNull("generated");
        column.putNull("onUpdate");
        return column;
    }

    /** 식의 바깥 괄호 한 겹씩 — content는 괄호 없는 식을 둔다 */
    static String stripOuterParens(String expression) {
        String text = expression.strip();
        while (text.startsWith("(") && text.endsWith(")")) {
            int depth = 0;
            boolean wraps = true;
            for (int i = 0; i < text.length() && wraps; i++) {
                char c = text.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    wraps = depth > 0 || i == text.length() - 1;
                }
            }
            if (!wraps) {
                break;
            }
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }

    /** CHECK 제약 — 이름이 같으면 식을 바꾸고, 없으면 더한다. 이름은 문서 전체 키 이름과 겹치면 안 된다 */
    private void applyCheck(String at, ObjectNode table, CheckItem item) {
        if (item == null || item.expression() == null || item.expression().isBlank()) {
            error(at + ".expression", "CHECK 식이 있어야 합니다");
            return;
        }
        if (item.expression().length() > 2000) {
            error(at + ".expression", "CHECK 식은 2000자 이하여야 합니다");
            return;
        }
        String expression = stripOuterParens(item.expression());
        String tableName = table.path("physicalName").asText();
        if (!table.path("checks").isArray()) {
            table.putArray("checks");
        }
        if (item.name() != null) {
            for (JsonNode check : table.path("checks")) {
                if (check.path("name").asText("").equalsIgnoreCase(item.name())) {
                    if (!expression.equals(check.path("expression").asText())) {
                        ((ObjectNode) check).put("expression", expression);
                        changes.add(new Change("check", "update", tableName, check.path("name").asText()));
                    }
                    return;
                }
            }
        }
        String name;
        if (item.name() != null && !item.name().isBlank()) {
            name = keyName(at, table, item.name(), "ck", List.of());
            if (name == null) {
                return;
            }
        } else {
            Set<String> names = documentKeyNames();
            String base = "ck_" + lower(tableName);
            int n = 1;
            while (names.contains(base + "_" + n)) {
                n++;
            }
            name = base + "_" + n;
        }
        ObjectNode check = ((ArrayNode) table.get("checks")).addObject();
        check.put("id", ids.get());
        check.put("name", name);
        check.put("expression", expression);
        changes.add(new Change("check", "add", tableName, name));
    }

    private static void putInt(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /* ---------- 키 ---------- */

    /** 기본 키를 준 목록으로 바꾼다 — PK 컬럼은 NOT NULL, 컬럼 순서는 PK → FK → 일반 */
    private void applyPrimaryKey(String at, ObjectNode table, List<String> names) {
        List<String> columnIds = resolveColumnIds(at, table, names);
        if (columnIds == null) {
            return;
        }
        List<String> beforeIds = primaryKeyIds(table);
        if (columnIds.equals(beforeIds)) {
            return;
        }
        String tableName = table.path("physicalName").asText();
        if (columnIds.isEmpty()) {
            for (JsonNode column : table.path("columns")) {
                if (beforeIds.contains(column.path("id").asText())) {
                    ((ObjectNode) column).put("autoIncrement", false);
                }
            }
            table.putNull("primaryKey");
        } else {
            String name = table.path("primaryKey").path("name").isTextual()
                    ? table.path("primaryKey").path("name").asText()
                    : nextName(documentKeyNames(), tableName.toLowerCase(Locale.ROOT) + "_pk");
            ObjectNode primaryKey = table.putObject("primaryKey");
            primaryKey.put("name", name);
            ArrayNode idsNode = primaryKey.putArray("columnIds");
            columnIds.forEach(idsNode::add);
            for (JsonNode column : table.path("columns")) {
                String id = column.path("id").asText();
                if (columnIds.contains(id)) {
                    ((ObjectNode) column).put("nullable", false);
                } else if (beforeIds.contains(id)) {
                    // 기본 키에서 빠진 컬럼 — 자동 증가를 푼다
                    ((ObjectNode) column).put("autoIncrement", false);
                }
                // 복합 기본 키에는 자동 증가를 둘 수 없다
                if (columnIds.size() > 1 && columnIds.contains(id)) {
                    ((ObjectNode) column).put("autoIncrement", false);
                }
            }
            orderColumns(table, columnIds);
        }
        changes.add(new Change("primaryKey", "update", tableName, tableName));
    }

    /** 컬럼 순서 — PK(키 정의 순서) → FK(지금 순서 유지) → 일반(지금 순서 유지) */
    private void orderColumns(ObjectNode table, List<String> primaryKeyIds) {
        ArrayNode columns = (ArrayNode) table.get("columns");
        Map<String, JsonNode> byId = new HashMap<>();
        columns.forEach(column -> byId.put(column.path("id").asText(), column));
        Set<String> fkIds = foreignKeyColumnIds(table);
        List<JsonNode> ordered = new ArrayList<>();
        for (String id : primaryKeyIds) {
            if (byId.containsKey(id)) {
                ordered.add(byId.get(id));
            }
        }
        for (JsonNode column : columns) {
            String id = column.path("id").asText();
            if (!primaryKeyIds.contains(id) && fkIds.contains(id)) {
                ordered.add(column);
            }
        }
        for (JsonNode column : columns) {
            String id = column.path("id").asText();
            if (!primaryKeyIds.contains(id) && !fkIds.contains(id)) {
                ordered.add(column);
            }
        }
        columns.removeAll();
        ordered.forEach(columns::add);
    }

    /** 자동 증가는 정수 타입의 단일 컬럼 기본 키에만 둔다 */
    private void checkAutoIncrement(String at, ObjectNode table) {
        List<String> pk = primaryKeyIds(table);
        for (JsonNode column : table.path("columns")) {
            if (!column.path("autoIncrement").asBoolean(false)) {
                continue;
            }
            boolean single = pk.size() == 1 && pk.get(0).equals(column.path("id").asText());
            if (!single || !INTEGER_TYPES.contains(column.path("dataType").asText(""))) {
                error(at + ".columns", "자동 증가는 정수 타입의 단일 컬럼 기본 키에만 씁니다: " + column.path("physicalName").asText());
            }
        }
    }

    private void applyUnique(String at, ObjectNode table, UniqueItem item) {
        if (item == null || isEmpty(item.columns())) {
            error(at + ".columns", "유니크 키의 컬럼이 있어야 합니다");
            return;
        }
        List<String> columnIds = resolveColumnIds(at + ".columns", table, item.columns());
        if (columnIds == null) {
            return;
        }
        for (JsonNode unique : table.path("uniques")) {
            if (strings(unique.path("columnIds")).equals(columnIds)) {
                return;
            }
        }
        String name = keyName(at, table, item.name(), "uk", columnIds);
        if (name == null) {
            return;
        }
        ObjectNode unique = ((ArrayNode) table.get("uniques")).addObject();
        unique.put("id", ids.get());
        unique.put("name", name);
        ArrayNode idsNode = unique.putArray("columnIds");
        columnIds.forEach(idsNode::add);
        changes.add(new Change("uniqueKey", "add", table.path("physicalName").asText(), name));
    }

    private void applyIndex(String at, ObjectNode table, IndexItem item) {
        if (item == null) {
            error(at + ".columns", "인덱스의 컬럼이 있어야 합니다");
            return;
        }
        String tableName = table.path("physicalName").asText();
        // 이름이 같은 인덱스가 이 테이블에 있으면 그것을 고친다(v1.37 — 식·조건 인덱스는 컬럼으로 고를 수 없다)
        ObjectNode named = null;
        if (item.name() != null && !item.name().isBlank()) {
            for (JsonNode index : table.path("indexes")) {
                if (item.name().equalsIgnoreCase(index.path("name").asText(""))) {
                    named = (ObjectNode) index;
                }
            }
        }
        String expression = item.expression() != null ? blankToNull(item.expression())
                : named != null && isEmpty(item.columns()) ? textOrNull(named, "expression") : null;
        if (expression != null && !isEmpty(item.columns())) {
            error(at + ".expression", "expression과 columns는 함께 쓰지 않습니다 — 식이 든 키는 expression에 키 목록 전체를 적습니다");
            return;
        }
        if (expression == null && isEmpty(item.columns()) && (named == null || named.path("columns").isEmpty())) {
            error(at + ".columns", "인덱스의 컬럼이 있어야 합니다");
            return;
        }
        if (expression != null && expression.length() > 2000) {
            error(at + ".expression", "식은 2000자 이하여야 합니다");
            return;
        }
        String indexType = item.type() != null ? item.type().toUpperCase(Locale.ROOT)
                : named != null ? named.path("type").asText("BTREE") : "BTREE";
        if (!INDEX_TYPES.contains(indexType)) {
            error(at + ".type", "인덱스 종류는 " + String.join("·", INDEX_TYPES.stream().sorted().toList()) + "입니다: " + item.type());
            return;
        }
        String parser = item.parser() != null ? blankToNull(item.parser())
                : named != null && "FULLTEXT".equals(indexType) ? textOrNull(named, "parser") : null;
        if (parser != null && !"FULLTEXT".equals(indexType)) {
            error(at + ".parser", "parser는 FULLTEXT 인덱스에만 넣습니다");
            return;
        }
        if (parser != null && !parser.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            error(at + ".parser", "parser는 파서 이름(영문·숫자·밑줄)입니다: " + parser);
            return;
        }
        String where = item.where() != null ? blankToNull(stripOuterParens(item.where()))
                : named != null ? textOrNull(named, "where") : null;
        if (where != null && where.length() > 2000) {
            error(at + ".where", "조건은 2000자 이하여야 합니다");
            return;
        }
        boolean unique = item.unique() != null ? item.unique() : named != null && named.path("unique").asBoolean(false);
        List<String> includeIds = null;
        if (item.include() != null) {
            includeIds = item.include().isEmpty() ? List.of() : resolveColumnIds(at + ".include", table, item.include());
            if (includeIds == null) {
                return;
            }
        }
        // 키 컬럼 — 주지 않으면(이름으로 고친 인덱스) 그대로 둔다
        List<String> columnIds = null;
        List<String> orders = new ArrayList<>();
        List<String> opclasses = new ArrayList<>();
        if (!isEmpty(item.columns())) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < item.columns().size(); i++) {
                IndexColumnItem column = item.columns().get(i);
                if (column == null || column.name() == null) {
                    error(at + ".columns[" + i + "].name", "인덱스 컬럼의 이름이 있어야 합니다");
                    return;
                }
                String order = column.order() == null ? "ASC" : column.order();
                if (!order.equals("ASC") && !order.equals("DESC")) {
                    error(at + ".columns[" + i + "].order", "정렬은 ASC 또는 DESC입니다: " + order);
                    return;
                }
                String opclass = blankToNull(column.opclass());
                if (opclass != null && !OPCLASS.matcher(opclass).matches()) {
                    error(at + ".columns[" + i + "].opclass", "연산자 클래스 이름(영문·숫자·밑줄)입니다: " + opclass);
                    return;
                }
                names.add(column.name());
                orders.add(order);
                opclasses.add(opclass);
            }
            columnIds = resolveColumnIds(at + ".columns", table, names);
            if (columnIds == null) {
                return;
            }
        }

        ObjectNode target = named;
        if (target == null && columnIds != null && expression == null && where == null) {
            // 이름 없이 컬럼으로 고른다 — 같은 컬럼 조합·같은 종류의 조건 없는 인덱스. title의 FULLTEXT와 BTREE는 함께 둘 수 있다
            for (JsonNode index : table.path("indexes")) {
                List<String> existing = new ArrayList<>();
                index.path("columns").forEach(column -> existing.add(column.path("columnId").asText()));
                if (existing.equals(columnIds) && indexType.equals(index.path("type").asText("BTREE"))
                        && !index.hasNonNull("where") && !index.hasNonNull("expression")) {
                    target = (ObjectNode) index;
                }
            }
        }
        if (target == null) {
            String name = keyName(at, table, item.name(), unique ? "uk" : "idx", columnIds == null ? List.of() : columnIds);
            if (name == null) {
                return;
            }
            ObjectNode added = addIndex(table, name, columnIds == null ? List.of() : columnIds, orders);
            writeIndex(added, indexType, parser, unique, expression, where, includeIds, columnIds == null ? null : opclasses);
            changes.add(new Change("index", "add", tableName, name));
            return;
        }
        String before = target.toString();
        if (columnIds != null) {
            ArrayNode columns = target.putArray("columns");
            for (int i = 0; i < columnIds.size(); i++) {
                columns.addObject().put("columnId", columnIds.get(i)).put("order", orders.get(i));
            }
        } else if (expression != null) {
            target.putArray("columns");
        }
        writeIndex(target, indexType, parser, unique, expression, where, includeIds, columnIds == null ? null : opclasses);
        if (!before.equals(target.toString())) {
            changes.add(new Change("index", "update", tableName, target.path("name").asText()));
        }
    }

    /** 인덱스 속성 쓰기 — v1.37 속성은 값이 있을 때만 둔다(없는 인덱스는 이전과 같은 모양). include·opclasses가 null이면 그대로 */
    private static void writeIndex(ObjectNode index, String type, String parser, boolean unique, String expression,
                                   String where, List<String> includeIds, List<String> opclasses) {
        index.put("type", type);
        putText(index, "parser", parser);
        if (unique) {
            index.put("unique", true);
        } else {
            index.remove("unique");
        }
        if (expression != null) {
            index.put("expression", expression);
        } else {
            index.remove("expression");
        }
        if (where != null) {
            index.put("where", where);
        } else {
            index.remove("where");
        }
        if (includeIds != null) {
            if (includeIds.isEmpty()) {
                index.remove("include");
            } else {
                ArrayNode include = index.putArray("include");
                includeIds.forEach(include::add);
            }
        }
        if (opclasses != null) {
            int i = 0;
            for (JsonNode column : index.path("columns")) {
                String opclass = i < opclasses.size() ? opclasses.get(i) : null;
                if (opclass != null) {
                    ((ObjectNode) column).put("opclass", opclass);
                } else {
                    ((ObjectNode) column).remove("opclass");
                }
                i++;
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private ObjectNode addIndex(ObjectNode table, String name, List<String> columnIds, List<String> orders) {
        ObjectNode index = ((ArrayNode) table.get("indexes")).addObject();
        index.put("id", ids.get());
        index.put("name", name);
        ArrayNode columns = index.putArray("columns");
        for (int i = 0; i < columnIds.size(); i++) {
            ObjectNode column = columns.addObject();
            column.put("columnId", columnIds.get(i));
            column.put("order", orders == null ? "ASC" : orders.get(i));
        }
        index.put("type", "BTREE");
        index.putNull("parser");
        return index;
    }

    /** 키 이름 — 준 이름은 문서 전체에서 유일해야 하고, 생략하면 기본 이름을 만든다(겹치면 _1, _2…) */
    private String keyName(String at, ObjectNode table, String given, String prefix, List<String> columnIds) {
        Set<String> names = documentKeyNames();
        if (given != null && !given.isBlank()) {
            if (given.length() > 63) {
                error(at + ".name", "키 이름은 63자 이하여야 합니다");
                return null;
            }
            if (names.contains(given.toLowerCase(Locale.ROOT))) {
                error(at + ".name", "문서에 같은 키 이름이 있습니다: " + given);
                return null;
            }
            return given;
        }
        return defaultKeyName(names, table, prefix, columnIds);
    }

    /** 기본 키 이름 — {uk|idx}_{테이블}_{컬럼…} 소문자, 문서 전체 키 이름과 겹치면 접미를 붙인다 (keys.ts) */
    private String defaultKeyName(Set<String> names, ObjectNode table, String prefix, List<String> columnIds) {
        StringBuilder base = new StringBuilder(prefix).append('_').append(lower(table.path("physicalName").asText("table")));
        for (String id : columnIds) {
            JsonNode column = columnById(table, id);
            base.append('_').append(column == null ? "column" : lower(column.path("physicalName").asText("column")));
        }
        return nextName(names, base.toString());
    }

    /** 문서 전체 키 이름(소문자) — PK·UK·인덱스·FK 제약 이름이 한 네임스페이스다 (keys.ts) */
    private Set<String> documentKeyNames() {
        Set<String> names = new HashSet<>();
        for (JsonNode table : tables) {
            if (table.path("primaryKey").path("name").isTextual()) {
                names.add(lower(table.path("primaryKey").path("name").asText()));
            }
            table.path("uniques").forEach(unique -> names.add(lower(unique.path("name").asText(""))));
            table.path("indexes").forEach(index -> names.add(lower(index.path("name").asText(""))));
            table.path("checks").forEach(check -> names.add(lower(check.path("name").asText(""))));
        }
        relationships.forEach(relationship -> names.add(lower(relationship.path("fkName").asText(""))));
        return names;
    }

    private static String nextName(Set<String> existing, String base) {
        if (!existing.contains(lower(base))) {
            return base;
        }
        for (int i = 1; ; i++) {
            String candidate = base + "_" + i;
            if (!existing.contains(lower(candidate))) {
                return candidate;
            }
        }
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /* ---------- 요구사항 연결 ---------- */

    /** 테이블을 요구사항에 연결하고 반영한 것으로 표시한다 */
    private boolean linkRequirement(String at, ObjectNode table, String code) {
        ObjectNode requirement = code == null ? null : requirement(code);
        if (requirement == null) {
            error(at, "요구사항이 문서에 없습니다: " + code + ". save_requirements로 먼저 등록합니다");
            return false;
        }
        if ("document".equals(requirement.path("scope").asText(""))) {
            error(at, "공통 요구사항(scope=document)에는 테이블을 연결할 수 없습니다: " + code);
            return false;
        }
        String tableId = table.path("id").asText();
        List<String> tableIds = strings(requirement.path("tableIds"));
        int revision = requirement.path("revision").asInt(1);
        boolean changed = false;
        if (!tableIds.contains(tableId)) {
            ArrayNode array = requirement.get("tableIds") instanceof ArrayNode existing ? existing : requirement.putArray("tableIds");
            array.add(tableId);
            changed = true;
        }
        if (requirement.path("appliedRevision").asInt(0) != revision) {
            requirement.put("appliedRevision", revision);
            changed = true;
        }
        if (changed && changes.stream().noneMatch(change -> "requirement".equals(change.kind()) && code.equals(change.name()))) {
            changes.add(new Change("requirement", "update", "", code));
        }
        placeInRequirementArea(requirement, List.of(tableId));
        return true;
    }

    /**
     * 그룹이 없는 테이블을 요구사항 도메인의 그룹에 넣는다(v1.39 — 08-core/17-model-edit.md Section 2.2).
     * 이미 다른 그룹에 있는 테이블은 옮기지 않는다. 같은 요청의 areas가 뒤에 적용되므로 명시한 그룹이 이긴다
     */
    private void placeInRequirementArea(ObjectNode requirement, List<String> tableIds) {
        String areaId = requirement.path("areaId").isTextual() ? requirement.path("areaId").asText() : null;
        ObjectNode area = null;
        Set<String> grouped = new HashSet<>();
        for (JsonNode node : areas) {
            if (node.path("id").asText().equals(areaId)) {
                area = (ObjectNode) node;
            }
            grouped.addAll(strings(node.path("tableIds")));
        }
        if (area == null) {
            return;
        }
        ArrayNode members = area.get("tableIds") instanceof ArrayNode existing ? existing : area.putArray("tableIds");
        boolean added = false;
        for (String tableId : tableIds) {
            if (grouped.add(tableId)) {
                members.add(tableId);
                added = true;
            }
        }
        String name = area.path("name").asText();
        if (added && changes.stream().noneMatch(change -> "area".equals(change.kind()) && name.equals(change.name()))) {
            changes.add(new Change("area", "update", "", name));
        }
    }

    private boolean isTraced(String tableId) {
        for (JsonNode requirement : requirements) {
            if (strings(requirement.path("tableIds")).contains(tableId)) {
                return true;
            }
        }
        return false;
    }

    /* ---------- 관계 ---------- */

    private void applyRelationship(String at, RelationshipItem item) {
        if (item == null || item.parent() == null || item.child() == null) {
            error(at, "관계에는 parent와 child가 있어야 합니다");
            return;
        }
        ObjectNode parent = table(item.parent());
        ObjectNode child = table(item.child());
        if (parent == null) {
            error(at + ".parent", "테이블이 없습니다: " + item.parent());
            return;
        }
        if (child == null) {
            error(at + ".child", "테이블이 없습니다: " + item.child());
            return;
        }
        List<String> parentPk = primaryKeyIds(parent);
        if (parentPk.isEmpty()) {
            error(at + ".parent", "부모 테이블에 기본 키가 없어 관계를 만들 수 없습니다: " + item.parent());
            return;
        }
        // 기존 컬럼을 외래 키로 쓰는 매핑 — 부모 PK 컬럼마다 자식 컬럼 하나
        List<String[]> mapping = null;
        if (item.columnMappings() != null) {
            mapping = resolveMappings(at + ".columnMappings", parent, child, parentPk, item.columnMappings());
            if (mapping == null) {
                return;
            }
        }
        List<ObjectNode> candidates = relationships(parent.path("id").asText(), child.path("id").asText());
        String givenName = item.name() == null || item.name().isBlank() ? null : item.name().trim();
        ObjectNode existing;
        if (givenName != null) {
            existing = candidates.stream().filter(r -> givenName.equalsIgnoreCase(r.path("fkName").asText(""))).findFirst().orElse(null);
        } else if (candidates.size() <= 1) {
            existing = candidates.isEmpty() ? null : candidates.get(0);
        } else {
            existing = mapping == null ? null : relationshipByChildColumns(candidates, mapping);
            if (existing == null) {
                error(at + ".name", "부모와 자식이 같은 관계가 여럿입니다 — name(외래 키 이름)으로 고르세요: "
                        + String.join(", ", candidates.stream().map(r -> r.path("fkName").asText()).toList()));
                return;
            }
        }
        String type = item.type() != null ? item.type() : existing != null ? existing.path("type").asText("ONE_TO_MANY") : "ONE_TO_MANY";
        if (!RELATIONSHIP_TYPES.contains(type)) {
            error(at + ".type", "관계 유형은 ONE_TO_MANY 또는 ONE_TO_ONE입니다. N:M은 연결 테이블과 1:N 두 개로 표현합니다: " + type);
            return;
        }
        boolean identifying = item.identifying() != null ? item.identifying() : existing != null && existing.path("identifying").asBoolean(false);
        String parentMultiplicity = item.parentMultiplicity() != null ? item.parentMultiplicity()
                : existing != null ? existing.path("parentMultiplicity").asText("EXACTLY_ONE") : "EXACTLY_ONE";
        if (!PARENT_MULTIPLICITIES.contains(parentMultiplicity)) {
            error(at + ".parentMultiplicity", "부모 기수는 EXACTLY_ONE 또는 ZERO_OR_ONE입니다: " + parentMultiplicity);
            return;
        }
        // 기본값은 에디터의 관계 생성 기본값과 같다(양쪽 모두 필수 — 1:1은 EXACTLY_ONE, 1:N은 ONE_OR_MORE).
        // 다르면 이 API로 만든 문서만 자식 쪽 표기가 ○로 찍혀 손으로 그린 문서와 달라 보인다(v1.32에서 맞춤)
        String defaultChild = "ONE_TO_ONE".equals(type) ? "EXACTLY_ONE" : "ONE_OR_MORE";
        String childMultiplicity = item.childMultiplicity() != null ? item.childMultiplicity()
                : existing != null && item.type() == null ? existing.path("childMultiplicity").asText(defaultChild) : defaultChild;
        if (!CHILD_MULTIPLICITIES.get(type).contains(childMultiplicity)) {
            error(at + ".childMultiplicity", "자식 기수가 관계 유형과 맞지 않습니다. " + type + "은 " + CHILD_MULTIPLICITIES.get(type) + " 가운데 하나입니다: " + childMultiplicity);
            return;
        }
        String onDelete = item.onDelete() != null ? item.onDelete() : existing != null ? existing.path("onDelete").asText("NO_ACTION") : "NO_ACTION";
        String onUpdate = item.onUpdate() != null ? item.onUpdate() : existing != null ? existing.path("onUpdate").asText("NO_ACTION") : "NO_ACTION";
        if (!REFERENTIAL_ACTIONS.contains(onDelete)) {
            error(at + ".onDelete", "참조 동작이 아닙니다: " + onDelete);
            return;
        }
        if (!REFERENTIAL_ACTIONS.contains(onUpdate)) {
            error(at + ".onUpdate", "참조 동작이 아닙니다: " + onUpdate);
            return;
        }
        String name = "fk_" + lower(child.path("physicalName").asText()) + "_" + lower(parent.path("physicalName").asText());
        if (existing == null && givenName != null) {
            name = keyName(at, child, givenName, "fk", List.of());
            if (name == null) {
                return;
            }
        }
        if (existing == null) {
            createRelationship(parent, child, parentPk, mapping, type, identifying, parentMultiplicity, childMultiplicity, onDelete, onUpdate, name);
        } else {
            updateRelationship(existing, child, mapping, type, identifying, parentMultiplicity, childMultiplicity, onDelete, onUpdate);
        }
    }

    /** columnMappings 입력 → [부모 컬럼 id, 자식 컬럼 id] (부모 PK 순서). 틀리면 사유를 남기고 null */
    private List<String[]> resolveMappings(String at, ObjectNode parent, ObjectNode child, List<String> parentPk, List<ColumnMappingItem> items) {
        Map<String, String> byParent = new HashMap<>();
        Set<String> usedChildren = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ColumnMappingItem item = items.get(i);
            ObjectNode parentColumn = item == null || item.parentColumn() == null ? null : column(parent, item.parentColumn());
            ObjectNode childColumn = item == null || item.childColumn() == null ? null : column(child, item.childColumn());
            if (parentColumn == null || !parentPk.contains(parentColumn.path("id").asText())) {
                error(at + "[" + i + "].parentColumn", "부모의 기본 키 컬럼이 아닙니다: " + (item == null ? null : item.parentColumn()));
                return null;
            }
            if (childColumn == null) {
                error(at + "[" + i + "].childColumn", "자식 테이블에 컬럼이 없습니다: " + (item == null ? null : item.childColumn()));
                return null;
            }
            for (String field : List.of("dataType", "length", "precision", "scale")) {
                if (!parentColumn.path(field).equals(childColumn.path(field))) {
                    error(at + "[" + i + "].childColumn", "자식 컬럼의 타입이 부모 컬럼과 다릅니다(" + field + "): " + item.childColumn());
                    return null;
                }
            }
            if (byParent.put(parentColumn.path("id").asText(), childColumn.path("id").asText()) != null
                    || !usedChildren.add(childColumn.path("id").asText())) {
                error(at + "[" + i + "]", "같은 컬럼을 두 번 연결할 수 없습니다");
                return null;
            }
        }
        if (byParent.size() != parentPk.size()) {
            error(at, "부모의 기본 키 컬럼을 빠짐없이 한 번씩 적어야 합니다");
            return null;
        }
        List<String[]> out = new ArrayList<>();
        for (String parentColumnId : parentPk) {
            out.add(new String[] {parentColumnId, byParent.get(parentColumnId)});
        }
        return out;
    }

    private void createRelationship(ObjectNode parent, ObjectNode child, List<String> parentPk, List<String[]> mapping, String type,
                                    boolean identifying, String parentMultiplicity, String childMultiplicity, String onDelete,
                                    String onUpdate, String baseName) {
        String parentName = lower(parent.path("physicalName").asText());
        String childName = child.path("physicalName").asText();
        // 키 이름은 관계를 더하기 전의 문서 기준으로 정한다(에디터의 applyRelationshipCreate와 같다)
        Set<String> keyNames = documentKeyNames();
        String fkName = nextName(keyNames, baseName);
        ArrayNode childColumns = (ArrayNode) child.get("columns");
        List<String> fkIds = new ArrayList<>();
        List<String[]> mappings = new ArrayList<>();
        if (mapping == null) {
            // 외래 키 컬럼을 만든다 — {부모 테이블}_{부모 PK 컬럼}, 타입·길이·논리명은 부모 컬럼을 복사한다 (relationship.ts)
            Set<String> existingNames = new HashSet<>();
            childColumns.forEach(column -> existingNames.add(lower(column.path("physicalName").asText())));
            List<ObjectNode> fkColumns = new ArrayList<>();
            for (String parentColumnId : parentPk) {
                JsonNode parentColumn = columnById(parent, parentColumnId);
                String base = parentName + "_" + lower(parentColumn.path("physicalName").asText());
                String physicalName = base;
                for (int i = 1; existingNames.contains(physicalName); i++) {
                    physicalName = base + "_" + i;
                }
                existingNames.add(physicalName);
                ObjectNode column = columnNode(physicalName);
                column.put("logicalName", parentColumn.path("logicalName").asText(""));
                column.put("dataType", parentColumn.path("dataType").asText("VARCHAR"));
                column.set("length", parentColumn.path("length").isNumber() ? parentColumn.path("length") : root.nullNode());
                column.set("precision", parentColumn.path("precision").isNumber() ? parentColumn.path("precision") : root.nullNode());
                column.set("scale", parentColumn.path("scale").isNumber() ? parentColumn.path("scale") : root.nullNode());
                column.put("nullable", !identifying && "ZERO_OR_ONE".equals(parentMultiplicity));
                fkColumns.add(column);
                fkIds.add(column.path("id").asText());
                mappings.add(new String[] {parentColumnId, column.path("id").asText()});
            }
            List<String> pkBefore = primaryKeyIds(child);
            if (identifying) {
                extendPrimaryKey(child, fkIds);
            }
            // 선두의 연속한 PK 컬럼 뒤에 끼워 넣는다 — 식별 관계면 PK 블록 끝, 비식별이면 FK 영역 선두
            Set<String> pkNow = new HashSet<>(primaryKeyIds(child));
            int prefix = 0;
            for (JsonNode column : childColumns) {
                if (pkNow.contains(column.path("id").asText())) {
                    prefix++;
                } else {
                    break;
                }
            }
            for (int i = 0; i < fkColumns.size(); i++) {
                childColumns.insert(prefix + i, fkColumns.get(i));
            }
            if (identifying && primaryKeyIds(child).size() > 1) {
                clearAutoIncrement(child, pkBefore);
            }
        } else {
            for (String[] pair : mapping) {
                fkIds.add(pair[1]);
                mappings.add(pair);
            }
            hookForeignKey(child, fkIds, identifying, parentMultiplicity);
        }
        // 비식별 1:1은 FK 전체로 유니크 키를, 비식별 1:N은 FK로 인덱스를 만든다(인덱스는 DBMS가 스스로 만들지 않을 때만)
        syncOwnedKeys(child, fkIds, type, identifying, keyNames, true);

        ObjectNode relationship = relationships.addObject();
        relationship.put("id", ids.get());
        relationship.put("name", fkName);
        relationship.put("parentTableId", parent.path("id").asText());
        relationship.put("childTableId", child.path("id").asText());
        relationship.put("type", type);
        relationship.put("identifying", identifying);
        relationship.put("parentMultiplicity", parentMultiplicity);
        relationship.put("childMultiplicity", childMultiplicity);
        relationship.put("fkName", fkName);
        ArrayNode mappingNodes = relationship.putArray("columnMappings");
        for (String[] pair : mappings) {
            ObjectNode node = mappingNodes.addObject();
            node.put("parentColumnId", pair[0]);
            node.put("childColumnId", pair[1]);
        }
        relationship.put("onDelete", onDelete);
        relationship.put("onUpdate", onUpdate);
        changes.add(new Change("relationship", "add", childName, fkName));
    }

    private void updateRelationship(ObjectNode relationship, ObjectNode child, List<String[]> mapping, String type, boolean identifying,
                                    String parentMultiplicity, String childMultiplicity, String onDelete, String onUpdate) {
        String before = relationship.toString() + child.toString();
        String prevType = relationship.path("type").asText("ONE_TO_MANY");
        boolean prevIdentifying = relationship.path("identifying").asBoolean(false);
        String prevParentMultiplicity = relationship.path("parentMultiplicity").asText("EXACTLY_ONE");
        List<String> prevFkIds = new ArrayList<>();
        relationship.path("columnMappings").forEach(node -> prevFkIds.add(node.path("childColumnId").asText()));
        List<String> nextFkIds = new ArrayList<>(prevFkIds);
        boolean remapped = false;
        if (mapping != null) {
            nextFkIds.clear();
            mapping.forEach(pair -> nextFkIds.add(pair[1]));
            remapped = !sorted(prevFkIds).equals(sorted(nextFkIds));
        }
        relationship.put("type", type);
        relationship.put("identifying", identifying);
        relationship.put("parentMultiplicity", parentMultiplicity);
        relationship.put("childMultiplicity", childMultiplicity);
        relationship.put("onDelete", onDelete);
        relationship.put("onUpdate", onUpdate);
        if (mapping != null) {
            ArrayNode mappingNodes = relationship.putArray("columnMappings");
            for (String[] pair : mapping) {
                ObjectNode node = mappingNodes.addObject();
                node.put("parentColumnId", pair[0]);
                node.put("childColumnId", pair[1]);
            }
        }
        boolean structural = prevIdentifying != identifying || !prevType.equals(type) || !prevParentMultiplicity.equals(parentMultiplicity);
        if (remapped) {
            // 이전 외래 키 컬럼에서 관계가 만든 것을 걷고 새 컬럼에 관계의 규칙을 건다 (changes.ts rekeyForeignKey)
            unhookForeignKey(child, prevFkIds, nextFkIds, prevIdentifying);
            hookForeignKey(child, nextFkIds, identifying, parentMultiplicity);
            syncOwnedKeys(child, nextFkIds, type, identifying, documentKeyNames(), true);
        } else if (structural) {
            // 유형·식별 여부·부모 기수가 바뀌었다 — FK의 NULL 허용과 PK 소속, 관계가 만든 키를 맞춘다 (changes.ts applyRelationshipPatch)
            if (prevIdentifying != identifying) {
                if (identifying) {
                    List<String> pkBefore = primaryKeyIds(child);
                    extendPrimaryKey(child, nextFkIds);
                    setNullable(child, nextFkIds, false);
                    if (primaryKeyIds(child).size() > 1) {
                        clearAutoIncrement(child, pkBefore);
                    }
                } else {
                    shrinkPrimaryKey(child, nextFkIds);
                    setNullable(child, nextFkIds, "ZERO_OR_ONE".equals(parentMultiplicity));
                }
            } else if (!prevParentMultiplicity.equals(parentMultiplicity) && !identifying) {
                setNullable(child, nextFkIds, "ZERO_OR_ONE".equals(parentMultiplicity));
            }
            reorderByArea(child, nextFkIds);
            syncOwnedKeys(child, nextFkIds, type, identifying, documentKeyNames(), false);
        }
        if (!(relationship.toString() + child.toString()).equals(before)) {
            changes.add(new Change("relationship", "update", child.path("physicalName").asText(), relationship.path("fkName").asText()));
        }
    }

    /** 걷기 — 식별 관계였으면 풀린 컬럼을 기본 키에서 빼고, 이전 외래 키 집합과 정확히 일치하는 UK·인덱스를 지운다 */
    private void unhookForeignKey(ObjectNode table, List<String> prevFkIds, List<String> nextFkIds, boolean prevIdentifying) {
        if (prevIdentifying) {
            List<String> released = new ArrayList<>(prevFkIds);
            released.removeAll(nextFkIds);
            shrinkPrimaryKey(table, released);
        }
        List<String> prevKey = sorted(prevFkIds);
        removeMatching((ArrayNode) table.get("uniques"), unique -> sorted(strings(unique.path("columnIds"))).equals(prevKey));
        removeMatching((ArrayNode) table.get("indexes"), index -> ownsForeignKey(index) && sorted(indexColumnIds(index)).equals(prevKey));
    }

    /** 외래 키를 맡는 인덱스가 될 수 있는지 — 부분·식·유니크 인덱스는 사용자가 따로 만든 것이다(v1.37) */
    private static boolean ownsForeignKey(JsonNode index) {
        return !index.hasNonNull("where") && !index.hasNonNull("expression") && !index.path("unique").asBoolean(false);
    }

    /** 걸기 — 식별 관계면 기본 키에 넣고 NOT NULL, 아니면 부모 기수로 NULL 허용을 정한다. 그 뒤 PK → FK → 일반 순서로 다시 놓는다 */
    private void hookForeignKey(ObjectNode table, List<String> fkIds, boolean identifying, String parentMultiplicity) {
        if (identifying) {
            List<String> pkBefore = primaryKeyIds(table);
            extendPrimaryKey(table, fkIds);
            boolean composite = primaryKeyIds(table).size() > 1;
            for (JsonNode column : table.path("columns")) {
                String id = column.path("id").asText();
                if (fkIds.contains(id)) {
                    ((ObjectNode) column).put("nullable", false);
                    ((ObjectNode) column).put("autoIncrement", false);
                } else if (composite && pkBefore.contains(id)) {
                    ((ObjectNode) column).put("autoIncrement", false);
                }
            }
        } else {
            List<String> pkNow = primaryKeyIds(table);
            boolean nullable = "ZERO_OR_ONE".equals(parentMultiplicity);
            for (JsonNode column : table.path("columns")) {
                String id = column.path("id").asText();
                // 자식의 기본 키이기도 한 컬럼은 NOT NULL을 지킨다
                if (fkIds.contains(id) && !pkNow.contains(id)) {
                    ((ObjectNode) column).put("nullable", nullable);
                }
            }
        }
        reorderByArea(table, fkIds);
    }

    /**
     * 관계가 소유하는 키 맞추기 — 비식별 1:1은 FK 전체의 유니크 키, 비식별 1:N은 FK 인덱스(05-editor/01-core.md Section 6.6).
     * 소유 판정은 컬럼 집합이 FK 집합과 정확히 일치하는 것이다. 사용자가 직접 만든 키(집합이 다른 키)는 건드리지 않는다.
     *
     * @param keepUnwanted true면 원하지 않게 된 키를 지우지 않는다(생성·재매핑 — 걷기에서 이미 지웠다)
     */
    private void syncOwnedKeys(ObjectNode table, List<String> fkIds, String type, boolean identifying, Set<String> keyNames, boolean keepUnwanted) {
        if (fkIds.isEmpty()) {
            return;
        }
        List<String> fkKey = sorted(fkIds);
        ArrayNode uniques = (ArrayNode) table.get("uniques");
        ArrayNode indexes = (ArrayNode) table.get("indexes");
        boolean ownedUnique = false;
        for (JsonNode unique : uniques) {
            ownedUnique |= sorted(strings(unique.path("columnIds"))).equals(fkKey);
        }
        boolean wantUnique = "ONE_TO_ONE".equals(type) && !identifying;
        if (wantUnique && !ownedUnique) {
            ObjectNode unique = uniques.addObject();
            unique.put("id", ids.get());
            unique.put("name", defaultKeyName(keyNames, table, "uk", fkIds));
            ArrayNode idsNode = unique.putArray("columnIds");
            fkIds.forEach(idsNode::add);
        } else if (!wantUnique && ownedUnique && !keepUnwanted) {
            removeMatching(uniques, unique -> sorted(strings(unique.path("columnIds"))).equals(fkKey));
        }
        boolean ownedIndex = false;
        for (JsonNode index : indexes) {
            ownedIndex |= ownsForeignKey(index) && sorted(indexColumnIds(index)).equals(fkKey);
        }
        boolean wantIndex = "ONE_TO_MANY".equals(type) && !identifying && !autoIndexesForeignKey();
        if (wantIndex && !ownedIndex) {
            addIndex(table, defaultKeyName(keyNames, table, "idx", fkIds), fkIds, null);
        } else if (!wantIndex && ownedIndex && !keepUnwanted) {
            removeMatching(indexes, index -> ownsForeignKey(index) && sorted(indexColumnIds(index)).equals(fkKey));
        }
    }

    /** FK 선언이 자식 인덱스를 스스로 만드는 DBMS인가 — MySQL(InnoDB). 에디터의 dbmsAutoIndexesFk와 같은 분류다 */
    private boolean autoIndexesForeignKey() {
        return "mysql".equals(databaseType);
    }

    private void extendPrimaryKey(ObjectNode table, List<String> fkIds) {
        List<String> current = primaryKeyIds(table);
        if (current.isEmpty() && !table.path("primaryKey").isObject()) {
            ObjectNode primaryKey = table.putObject("primaryKey");
            // 에디터는 이 경우 테이블 물리명을 그대로 쓴다(소문자로 바꾸지 않는다)
            primaryKey.put("name", table.path("physicalName").asText() + "_pk");
            ArrayNode idsNode = primaryKey.putArray("columnIds");
            fkIds.forEach(idsNode::add);
            return;
        }
        ArrayNode idsNode = (ArrayNode) table.path("primaryKey").get("columnIds");
        for (String id : fkIds) {
            if (!current.contains(id)) {
                idsNode.add(id);
            }
        }
    }

    private void shrinkPrimaryKey(ObjectNode table, List<String> removeIds) {
        if (!table.path("primaryKey").isObject()) {
            return;
        }
        List<String> remain = new ArrayList<>(primaryKeyIds(table));
        remain.removeAll(removeIds);
        if (remain.isEmpty()) {
            table.putNull("primaryKey");
            return;
        }
        ArrayNode idsNode = ((ObjectNode) table.get("primaryKey")).putArray("columnIds");
        remain.forEach(idsNode::add);
    }

    private void setNullable(ObjectNode table, List<String> columnIds, boolean nullable) {
        for (JsonNode column : table.path("columns")) {
            if (columnIds.contains(column.path("id").asText())) {
                ((ObjectNode) column).put("nullable", nullable);
            }
        }
    }

    private void clearAutoIncrement(ObjectNode table, List<String> columnIds) {
        for (JsonNode column : table.path("columns")) {
            if (columnIds.contains(column.path("id").asText()) && column.path("autoIncrement").asBoolean(false)) {
                ((ObjectNode) column).put("autoIncrement", false);
            }
        }
    }

    /** 3영역 순서 재확정 — PK → 이 관계의 FK → 나머지 (같은 영역 안의 순서는 유지) */
    private void reorderByArea(ObjectNode table, List<String> fkIds) {
        ArrayNode columns = (ArrayNode) table.get("columns");
        List<String> pk = primaryKeyIds(table);
        List<JsonNode> ordered = new ArrayList<>();
        for (int rank = 0; rank < 3; rank++) {
            for (JsonNode column : columns) {
                String id = column.path("id").asText();
                int own = pk.contains(id) ? 0 : fkIds.contains(id) ? 1 : 2;
                if (own == rank) {
                    ordered.add(column);
                }
            }
        }
        columns.removeAll();
        ordered.forEach(columns::add);
    }

    /* ---------- 그룹 ---------- */

    private void applyArea(String at, AreaItem item) {
        if (item == null || item.name() == null || item.name().isBlank()) {
            error(at + ".name", "그룹 이름이 있어야 합니다");
            return;
        }
        String name = item.name().strip();
        if (name.length() > 100) {
            error(at + ".name", "그룹 이름은 100자 이하여야 합니다");
            return;
        }
        ObjectNode area = area(name);
        boolean created = area == null;
        String before = created ? null : area.toString();
        if (created) {
            if (item.rename() != null) {
                error(at + ".rename", "rename은 이미 있는 그룹에만 씁니다");
                return;
            }
            area = newArea(name);
        } else if (item.rename() != null && !item.rename().strip().equals(name)) {
            String rename = item.rename().strip();
            if (rename.isEmpty() || rename.length() > 100 || area(rename) != null) {
                error(at + ".rename", "그룹 이름이 비었거나 이미 있습니다: " + item.rename());
                return;
            }
            area.put("name", rename);
        }
        if (item.color() != null) {
            if (!COLORS.contains(item.color())) {
                error(at + ".color", "그룹 색이 아닙니다: " + item.color());
                return;
            }
            area.put("color", item.color());
        }
        if (item.description() != null) {
            if (item.description().length() > 500) {
                error(at + ".description", "그룹 설명은 500자 이하여야 합니다");
                return;
            }
            area.put("description", item.description());
        }
        if (item.tables() != null) {
            List<String> tableIds = resolveTableIds(at + ".tables", item.tables());
            if (tableIds == null) {
                return;
            }
            // 테이블은 한 그룹에만 속하게 한다 — 다른 그룹에 있던 테이블은 그 그룹에서 뺀다
            String areaId = area.path("id").asText();
            for (JsonNode other : areas) {
                if (areaId.equals(other.path("id").asText())) {
                    continue;
                }
                List<String> remain = strings(other.path("tableIds"));
                if (remain.removeAll(tableIds)) {
                    ArrayNode array = ((ObjectNode) other).putArray("tableIds");
                    remain.forEach(array::add);
                }
            }
            ArrayNode array = area.putArray("tableIds");
            tableIds.forEach(array::add);
        }
        String finalName = area.path("name").asText();
        if (created) {
            changes.add(new Change("area", "add", "", finalName));
        } else if (!area.toString().equals(before)) {
            changes.add(new Change("area", "update", "", finalName));
        }
    }

    /* =====================================================================
     * 삭제 (Section 3.4) — 에디터의 연쇄 정리와 같다
     * ===================================================================== */

    public void remove(List<String> tableNames, List<ColumnRef> columnRefs, List<RelationshipRef> relationshipRefs, List<String> requirementCodes) {
        remove(tableNames, columnRefs, relationshipRefs, requirementCodes, null);
    }

    public void remove(List<String> tableNames, List<ColumnRef> columnRefs, List<RelationshipRef> relationshipRefs, List<String> requirementCodes,
                       List<CheckRef> checkRefs) {
        remove(tableNames, columnRefs, relationshipRefs, requirementCodes, checkRefs, null);
    }

    public void remove(List<String> tableNames, List<ColumnRef> columnRefs, List<RelationshipRef> relationshipRefs, List<String> requirementCodes,
                       List<CheckRef> checkRefs, List<IndexRef> indexRefs) {
        if (isEmpty(tableNames) && isEmpty(columnRefs) && isEmpty(relationshipRefs) && isEmpty(requirementCodes) && isEmpty(checkRefs)
                && isEmpty(indexRefs)) {
            error("tables", "tables, columns, relationships, checks, indexes, requirements 가운데 하나는 있어야 합니다");
            return;
        }
        // 대상을 먼저 다 찾는다 — 하나라도 없으면 아무것도 지우지 않는다
        List<String> relationshipIds = new ArrayList<>();
        if (relationshipRefs != null) {
            for (int i = 0; i < relationshipRefs.size(); i++) {
                RelationshipRef ref = relationshipRefs.get(i);
                ObjectNode parent = ref == null || ref.parent() == null ? null : table(ref.parent());
                ObjectNode child = ref == null || ref.child() == null ? null : table(ref.child());
                List<ObjectNode> candidates = parent == null || child == null ? List.of()
                        : relationships(parent.path("id").asText(), child.path("id").asText());
                String name = ref == null || ref.name() == null || ref.name().isBlank() ? null : ref.name().trim();
                ObjectNode relationship = name != null
                        ? candidates.stream().filter(r -> name.equalsIgnoreCase(r.path("fkName").asText(""))).findFirst().orElse(null)
                        : candidates.size() == 1 ? candidates.get(0) : null;
                if (name == null && candidates.size() > 1) {
                    error("relationships[" + i + "].name", "부모와 자식이 같은 관계가 여럿입니다 — name(외래 키 이름)으로 고르세요: "
                            + String.join(", ", candidates.stream().map(r -> r.path("fkName").asText()).toList()));
                } else if (relationship == null) {
                    error("relationships[" + i + "]", "관계가 없습니다: " + (ref == null ? null : ref.parent() + " → " + ref.child()
                            + (name == null ? "" : " (" + name + ")")));
                } else {
                    relationshipIds.add(relationship.path("id").asText());
                }
            }
        }
        List<String[]> columnIds = new ArrayList<>();
        if (columnRefs != null) {
            for (int i = 0; i < columnRefs.size(); i++) {
                ColumnRef ref = columnRefs.get(i);
                ObjectNode table = ref == null || ref.table() == null ? null : table(ref.table());
                ObjectNode column = table == null || ref.column() == null ? null : column(table, ref.column());
                if (column == null) {
                    error("columns[" + i + "]", "컬럼이 없습니다: " + (ref == null ? null : ref.table() + "." + ref.column()));
                } else {
                    columnIds.add(new String[] {table.path("id").asText(), column.path("id").asText(), ref.table() + "." + ref.column()});
                }
            }
        }
        List<String> tableIds = new ArrayList<>();
        if (tableNames != null) {
            for (int i = 0; i < tableNames.size(); i++) {
                ObjectNode table = tableNames.get(i) == null ? null : table(tableNames.get(i));
                if (table == null) {
                    error("tables[" + i + "]", "테이블이 없습니다: " + tableNames.get(i));
                } else {
                    tableIds.add(table.path("id").asText());
                }
            }
        }
        List<String[]> checkIds = new ArrayList<>();
        if (checkRefs != null) {
            for (int i = 0; i < checkRefs.size(); i++) {
                CheckRef ref = checkRefs.get(i);
                ObjectNode table = ref == null || ref.table() == null ? null : table(ref.table());
                JsonNode check = null;
                if (table != null && ref.name() != null) {
                    for (JsonNode candidate : table.path("checks")) {
                        if (ref.name().equalsIgnoreCase(candidate.path("name").asText(""))) {
                            check = candidate;
                        }
                    }
                }
                if (check == null) {
                    error("checks[" + i + "]", "CHECK 제약이 없습니다: " + (ref == null ? null : ref.table() + "." + ref.name()));
                } else {
                    checkIds.add(new String[] {table.path("id").asText(), check.path("id").asText()});
                }
            }
        }
        List<String[]> indexIds = new ArrayList<>();
        if (indexRefs != null) {
            for (int i = 0; i < indexRefs.size(); i++) {
                IndexRef ref = indexRefs.get(i);
                ObjectNode table = ref == null || ref.table() == null ? null : table(ref.table());
                JsonNode index = null;
                if (table != null && ref.name() != null) {
                    for (JsonNode candidate : table.path("indexes")) {
                        if (ref.name().trim().equalsIgnoreCase(candidate.path("name").asText(""))) {
                            index = candidate;
                        }
                    }
                }
                if (index == null) {
                    error("indexes[" + i + "]", "인덱스가 없습니다: " + (ref == null ? null : ref.table() + "." + ref.name()));
                } else {
                    indexIds.add(new String[] {table.path("id").asText(), index.path("id").asText()});
                }
            }
        }
        if (requirementCodes != null) {
            for (int i = 0; i < requirementCodes.size(); i++) {
                if (requirementCodes.get(i) == null || requirement(requirementCodes.get(i)) == null) {
                    error("requirements[" + i + "]", "요구사항이 없습니다: " + requirementCodes.get(i));
                }
            }
        }
        if (!errors.isEmpty()) {
            return;
        }
        Set<String> linkedBefore = confirmedLinkedCodes();
        for (String id : relationshipIds) {
            ObjectNode relationship = relationshipById(id);
            if (relationship != null) {
                String name = relationship.path("fkName").asText();
                String childName = tableById(relationship.path("childTableId").asText()).path("physicalName").asText();
                removeRelationshipCascade(id);
                changes.add(new Change("relationship", "remove", childName, name));
            }
        }
        for (String[] ref : checkIds) {
            ObjectNode table = (ObjectNode) tableById(ref[0]);
            JsonNode check = checkById(table, ref[1]);
            if (check != null) {
                String name = check.path("name").asText();
                removeMatching((ArrayNode) table.get("checks"), node -> ref[1].equals(node.path("id").asText()));
                changes.add(new Change("check", "remove", table.path("physicalName").asText(), name));
            }
        }
        for (String[] ref : indexIds) {
            ObjectNode table = (ObjectNode) tableById(ref[0]);
            ArrayNode indexes = (ArrayNode) table.get("indexes");
            for (JsonNode index : indexes) {
                if (ref[1].equals(index.path("id").asText())) {
                    String name = index.path("name").asText();
                    removeMatching(indexes, node -> ref[1].equals(node.path("id").asText()));
                    changes.add(new Change("index", "remove", table.path("physicalName").asText(), name));
                    break;
                }
            }
        }
        for (String[] ref : columnIds) {
            // 앞의 관계 삭제로 이미 사라진 컬럼일 수 있다
            if (tableById(ref[0]) != null && columnById(tableById(ref[0]), ref[1]) != null) {
                removeColumnEverywhere(ref[0], ref[1]);
                changes.add(new Change("column", "remove", ref[2].substring(0, ref[2].indexOf('.')), ref[2].substring(ref[2].indexOf('.') + 1)));
            }
        }
        for (String id : tableIds) {
            JsonNode table = tableById(id);
            if (table != null) {
                String name = table.path("physicalName").asText();
                removeTableCascade(id);
                changes.add(new Change("table", "remove", name, name));
            }
        }
        if (requirementCodes != null) {
            for (String code : requirementCodes) {
                removeMatching(requirements, node -> code.equals(node.path("code").asText("")));
                changes.add(new Change("requirement", "remove", "", code));
            }
        }
        // 테이블이 하나도 남지 않게 된 확정 요구사항을 알린다
        Set<String> linkedAfter = confirmedLinkedCodes();
        for (String code : linkedBefore) {
            if (!linkedAfter.contains(code) && requirement(code) != null) {
                warnings.add(new Warning("UNLINKED_REQUIREMENT", code, "연결된 테이블이 모두 지워진 요구사항입니다: " + code));
            }
        }
    }

    private Set<String> confirmedLinkedCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode requirement : requirements) {
            if ("confirmed".equals(requirement.path("status").asText("")) && !requirement.path("tableIds").isEmpty()) {
                codes.add(requirement.path("code").asText());
            }
        }
        return codes;
    }

    /** 테이블 삭제 — 붙은 관계, 상대 테이블의 FK 컬럼, 위치, 메모의 연관, 그룹과 요구사항의 참조를 함께 정리한다 (changes.ts removeTableCascade) */
    private void removeTableCascade(String tableId) {
        List<JsonNode> attached = new ArrayList<>();
        for (JsonNode relationship : relationships) {
            if (tableId.equals(relationship.path("parentTableId").asText()) || tableId.equals(relationship.path("childTableId").asText())) {
                attached.add(relationship.deepCopy());
            }
        }
        removeMatching(tables, table -> tableId.equals(table.path("id").asText()));
        removeMatching(relationships, relationship -> tableId.equals(relationship.path("parentTableId").asText())
                || tableId.equals(relationship.path("childTableId").asText()));
        if (diagram.path("nodes").isObject()) {
            ((ObjectNode) diagram.get("nodes")).remove(tableId);
        }
        for (JsonNode note : diagram.path("notes")) {
            if (tableId.equals(note.path("linkedTableId").asText(""))) {
                ((ObjectNode) note).putNull("linkedTableId");
            }
        }
        for (ArrayNode owners : List.of(areas, requirements)) {
            for (JsonNode owner : owners) {
                List<String> remain = strings(owner.path("tableIds"));
                if (remain.remove(tableId)) {
                    ArrayNode array = ((ObjectNode) owner).putArray("tableIds");
                    remain.forEach(array::add);
                }
            }
        }
        // 관계가 만든 FK 컬럼 — 상대 테이블이 자식인 쪽에서 지운다
        for (JsonNode relationship : attached) {
            String childId = relationship.path("childTableId").asText();
            if (!childId.equals(tableId)) {
                for (JsonNode mapping : relationship.path("columnMappings")) {
                    removeColumnEverywhere(childId, mapping.path("childColumnId").asText());
                }
            }
        }
    }

    /** 관계 삭제 — 그 관계가 만든 FK 컬럼도 지운다(다른 관계가 쓰지 않을 때만) (changes.ts removeRelationshipCascade) */
    private void removeRelationshipCascade(String relationshipId) {
        ObjectNode relationship = relationshipById(relationshipId);
        if (relationship == null) {
            return;
        }
        JsonNode copy = relationship.deepCopy();
        removeMatching(relationships, node -> relationshipId.equals(node.path("id").asText()));
        String childId = copy.path("childTableId").asText();
        for (JsonNode mapping : copy.path("columnMappings")) {
            String childColumnId = mapping.path("childColumnId").asText();
            boolean stillReferenced = false;
            for (JsonNode other : relationships) {
                if (childId.equals(other.path("childTableId").asText())) {
                    for (JsonNode otherMapping : other.path("columnMappings")) {
                        stillReferenced |= childColumnId.equals(otherMapping.path("childColumnId").asText());
                    }
                }
            }
            if (!stillReferenced) {
                removeColumnEverywhere(childId, childColumnId);
            }
        }
    }

    /** 컬럼 삭제 — 그 컬럼이 관계의 매핑에 있으면 관계부터 지우고, 키에서 정리한다 (changes.ts removeColumnEverywhere) */
    private void removeColumnEverywhere(String tableId, String columnId) {
        List<String> owners = new ArrayList<>();
        for (JsonNode relationship : relationships) {
            boolean child = tableId.equals(relationship.path("childTableId").asText());
            boolean parent = tableId.equals(relationship.path("parentTableId").asText());
            for (JsonNode mapping : relationship.path("columnMappings")) {
                if ((child && columnId.equals(mapping.path("childColumnId").asText()))
                        || (parent && columnId.equals(mapping.path("parentColumnId").asText()))) {
                    owners.add(relationship.path("id").asText());
                    break;
                }
            }
        }
        if (!owners.isEmpty()) {
            owners.forEach(this::removeRelationshipCascade);
            // 부모 쪽 PK처럼 관계 삭제가 지우지 않는 컬럼이면 이어서 지운다
            if (tableById(tableId) != null && columnById(tableById(tableId), columnId) != null) {
                removeColumnEverywhere(tableId, columnId);
            }
            return;
        }
        ObjectNode table = (ObjectNode) tableById(tableId);
        if (table == null) {
            return;
        }
        JsonNode removed = columnById(table, columnId);
        String columnName = removed == null ? null : removed.path("physicalName").asText(null);
        removeMatching((ArrayNode) table.get("columns"), column -> columnId.equals(column.path("id").asText()));
        if (table.path("primaryKey").isObject()) {
            shrinkPrimaryKey(table, List.of(columnId));
        }
        // 지운 컬럼을 쓰는 CHECK도 지운다 — 남기면 DDL이 없는 컬럼을 참조한다(신고 45). 무엇을 지웠는지 경고로 알린다
        if (columnName != null && table.path("checks").isArray()) {
            String tableName = table.path("physicalName").asText();
            for (JsonNode check : table.path("checks")) {
                if (CheckExpressions.references(check.path("expression").asText(""), columnName)) {
                    String checkName = check.path("name").asText();
                    changes.add(new Change("check", "remove", tableName, checkName));
                    warnings.add(new Warning("CHECK_REMOVED_WITH_COLUMN", tableName + "." + checkName,
                            "지운 컬럼 " + tableName + "." + columnName + "을 쓰는 CHECK 제약도 지웠습니다: " + checkName
                                    + " (" + check.path("expression").asText("") + ")"));
                }
            }
            removeMatching((ArrayNode) table.get("checks"),
                    check -> CheckExpressions.references(check.path("expression").asText(""), columnName));
        }
        // 키에서 컬럼을 빼고, 남는 컬럼이 없으면 키를 지운다
        ArrayNode uniques = (ArrayNode) table.get("uniques");
        for (JsonNode unique : uniques) {
            List<String> remain = strings(unique.path("columnIds"));
            if (remain.remove(columnId)) {
                ArrayNode array = ((ObjectNode) unique).putArray("columnIds");
                remain.forEach(array::add);
            }
        }
        removeMatching(uniques, unique -> unique.path("columnIds").isEmpty());
        ArrayNode indexes = (ArrayNode) table.get("indexes");
        for (JsonNode index : indexes) {
            removeMatching((ArrayNode) index.get("columns"), column -> columnId.equals(column.path("columnId").asText()));
            if (index.path("include").isArray()) {
                removeMatching((ArrayNode) index.get("include"), id -> columnId.equals(id.asText()));
                if (index.path("include").isEmpty()) {
                    ((ObjectNode) index).remove("include");
                }
            }
        }
        // 식·조건이 지운 컬럼을 쓰는 인덱스도 지운다(v1.37 — CHECK와 같은 규칙). 식 인덱스는 컬럼 목록이 비어 있다
        removeMatching(indexes, index -> (index.path("columns").isEmpty() && !index.hasNonNull("expression"))
                || (columnName != null && (CheckExpressions.references(index.path("expression").asText(""), columnName)
                        || CheckExpressions.references(index.path("where").asText(""), columnName))));
    }

    /* =====================================================================
     * 찾기
     * ===================================================================== */

    private ObjectNode table(String physicalName) {
        for (JsonNode table : tables) {
            if (physicalName.equalsIgnoreCase(table.path("physicalName").asText(""))) {
                normalizeTable((ObjectNode) table);
                return (ObjectNode) table;
            }
        }
        return null;
    }

    /** 옛 문서에는 uniques·indexes가 없을 수 있다 — 에디터가 열 때 하는 정규화와 같다 */
    private static void normalizeTable(ObjectNode table) {
        if (!(table.get("uniques") instanceof ArrayNode)) {
            table.putArray("uniques");
        }
        if (!(table.get("indexes") instanceof ArrayNode)) {
            table.putArray("indexes");
        }
    }

    private JsonNode tableById(String id) {
        for (JsonNode table : tables) {
            if (id.equals(table.path("id").asText())) {
                normalizeTable((ObjectNode) table);
                return table;
            }
        }
        return null;
    }

    private static ObjectNode column(ObjectNode table, String physicalName) {
        for (JsonNode column : table.path("columns")) {
            if (physicalName.equalsIgnoreCase(column.path("physicalName").asText(""))) {
                return (ObjectNode) column;
            }
        }
        return null;
    }

    private static JsonNode columnById(JsonNode table, String id) {
        for (JsonNode column : table.path("columns")) {
            if (id.equals(column.path("id").asText())) {
                return column;
            }
        }
        return null;
    }

    /** 부모·자식이 같은 관계 전부 — 문서 순서 */
    private List<ObjectNode> relationships(String parentTableId, String childTableId) {
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode relationship : relationships) {
            if (parentTableId.equals(relationship.path("parentTableId").asText())
                    && childTableId.equals(relationship.path("childTableId").asText())) {
                out.add((ObjectNode) relationship);
            }
        }
        return out;
    }

    /** 외래 키 자식 컬럼 집합이 매핑과 같은 관계 — 없으면 null */
    private static ObjectNode relationshipByChildColumns(List<ObjectNode> candidates, List<String[]> mapping) {
        Set<String> wanted = new HashSet<>();
        mapping.forEach(pair -> wanted.add(pair[1]));
        for (ObjectNode relationship : candidates) {
            Set<String> current = new HashSet<>();
            relationship.path("columnMappings").forEach(node -> current.add(node.path("childColumnId").asText()));
            if (current.equals(wanted)) {
                return relationship;
            }
        }
        return null;
    }

    private static JsonNode checkById(JsonNode table, String id) {
        for (JsonNode check : table.path("checks")) {
            if (id.equals(check.path("id").asText())) {
                return check;
            }
        }
        return null;
    }

    private ObjectNode relationshipById(String id) {
        for (JsonNode relationship : relationships) {
            if (id.equals(relationship.path("id").asText())) {
                return (ObjectNode) relationship;
            }
        }
        return null;
    }

    private static List<String> primaryKeyIds(JsonNode table) {
        return strings(table.path("primaryKey").path("columnIds"));
    }

    /** 이 테이블이 자식인 관계의 FK 컬럼 id */
    private Set<String> foreignKeyColumnIds(JsonNode table) {
        Set<String> out = new HashSet<>();
        String tableId = table.path("id").asText();
        for (JsonNode relationship : relationships) {
            if (tableId.equals(relationship.path("childTableId").asText())) {
                relationship.path("columnMappings").forEach(mapping -> out.add(mapping.path("childColumnId").asText()));
            }
        }
        return out;
    }

    private boolean isForeignKeyColumn(JsonNode table, String columnId) {
        return foreignKeyColumnIds(table).contains(columnId);
    }

    /** 컬럼 물리명 목록 → id 목록. 없는 이름이 있으면 사유를 남기고 null */
    private List<String> resolveColumnIds(String at, ObjectNode table, List<String> names) {
        List<String> out = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < names.size(); i++) {
            ObjectNode column = names.get(i) == null ? null : column(table, names.get(i));
            if (column == null) {
                error(at + "[" + i + "]", "컬럼이 없습니다: " + table.path("physicalName").asText() + "." + names.get(i));
                ok = false;
            } else if (out.contains(column.path("id").asText())) {
                error(at + "[" + i + "]", "같은 컬럼이 두 번 있습니다: " + names.get(i));
                ok = false;
            } else {
                out.add(column.path("id").asText());
            }
        }
        return ok ? out : null;
    }

    private static List<String> indexColumnIds(JsonNode index) {
        List<String> out = new ArrayList<>();
        index.path("columns").forEach(column -> out.add(column.path("columnId").asText()));
        return out;
    }

    static List<String> strings(JsonNode array) {
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

    private static List<String> sorted(List<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(null);
        return out;
    }

    private static void removeMatching(ArrayNode array, java.util.function.Predicate<JsonNode> match) {
        for (int i = array.size() - 1; i >= 0; i--) {
            if (match.test(array.get(i))) {
                array.remove(i);
            }
        }
    }
}
