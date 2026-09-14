package net.java21.crowfoot.api.connection.reverse;

import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Introspection 결과 → Canonical content v1 조립 (05-editor/04-dbms-engineering.md Section 3.2).
 *
 * <p>정방향(DbmsTemplates)과 같은 원천의 역방향 매핑: 물리 타입은 introspector의
 * {@link SchemaIntrospector#commonTypeCode}로 공용 논리 코드로 바꾼다. 논리명은 DB 코멘트
 * (§3.2 규칙 — DB COMMENT ≡ 논리명, 없으면 물리명), 배치는 그리드(4열) 초기 좌표를 준다 —
 * 사용자가 elkjs 자동 배치로 다시 잡을 수 있다.
 *
 * <p>키 이름은 문서 전체 단일 네임스페이스(01-core.md Section 18)라 DB 제약 이름을 그대로
 * 쓰되, MySQL PK 상수명({@code PRIMARY})은 {@code PK_{테이블}}으로 정규화한다.
 */
@Component
public class ReverseContentAssembler {

    /** 그리드 배치 — 열 4개, 테이블 폭 여유를 감안한 간격 */
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_X_GAP = 360;
    private static final int GRID_Y_GAP = 320;
    private static final int GRID_MARGIN = 80;

    /** 길이(n)를 저장하는 공용 코드 — 나머지 타입의 length는 버린다 (dbms.ts 규칙과 동일) */
    private static final Set<String> LENGTH_TYPES = Set.of("CHAR", "VARCHAR");

    /** 정밀도(p,s)를 저장하는 공용 코드 */
    private static final Set<String> PRECISION_TYPES = Set.of("DECIMAL");

    /** MySQL PK 제약의 상수 이름 — 모든 테이블이 같아서 단일 네임스페이스 규칙 위반 */
    private static final String MYSQL_PRIMARY = "PRIMARY";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 조립 결과 — content JSON 문자열과 리버스 요약(08-core/06-connection.md Section 3.6) */
    public record AssembledContent(String content, int tableCount, int relationshipCount, List<String> skipped) {
    }

    public AssembledContent assemble(IntrospectedSchema schema, SchemaIntrospector introspector) {
        List<String> skipped = new ArrayList<>();

        Map<String, String> tableIds = new HashMap<>();
        Map<String, Map<String, String>> columnIds = new HashMap<>();
        Map<String, IntrospectedSchema.IntrospectedTable> tableByName = new HashMap<>();
        for (IntrospectedSchema.IntrospectedTable table : schema.tables()) {
            tableIds.put(table.name(), UUID.randomUUID().toString());
            tableByName.put(table.name(), table);
            Map<String, String> ids = new HashMap<>();
            table.columns().forEach(column -> ids.put(column.name(), UUID.randomUUID().toString()));
            columnIds.put(table.name(), ids);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        ObjectNode modelNode = root.putObject("model");
        ArrayNode tablesNode = modelNode.putArray("tables");
        for (IntrospectedSchema.IntrospectedTable table : schema.tables()) {
            tablesNode.add(table(table, introspector, tableIds.get(table.name()), columnIds.get(table.name())));
        }

        ArrayNode relationshipsNode = modelNode.putArray("relationships");
        for (IntrospectedSchema.IntrospectedFk fk : schema.foreignKeys()) {
            String parentTableId = tableIds.get(fk.parentTable());
            String childTableId = tableIds.get(fk.childTable());
            if (parentTableId == null || childTableId == null) {
                skipped.add(fk.name() + " (참조 테이블을 찾을 수 없음)");
                continue;
            }
            ObjectNode relationship = relationship(fk, parentTableId, childTableId,
                    tableByName.get(fk.childTable()), columnIds.get(fk.childTable()),
                    columnIds.get(fk.parentTable()));
            if (relationship == null) {
                skipped.add(fk.name() + " (FK 컬럼을 찾을 수 없음)");
                continue;
            }
            relationshipsNode.add(relationship);
        }

        ObjectNode diagramNode = root.putObject("diagram");
        ObjectNode nodesNode = diagramNode.putObject("nodes");
        int index = 0;
        for (IntrospectedSchema.IntrospectedTable table : schema.tables()) {
            ObjectNode layout = nodesNode.putObject(tableIds.get(table.name()));
            layout.put("x", GRID_MARGIN + (index % GRID_COLUMNS) * GRID_X_GAP);
            layout.put("y", GRID_MARGIN + (index / GRID_COLUMNS) * GRID_Y_GAP);
            layout.putNull("width");
            index++;
        }
        diagramNode.putArray("notes");
        diagramNode.putNull("viewport");

        return new AssembledContent(
                objectMapper.writeValueAsString(root), schema.tables().size(), relationshipsNode.size(),
                List.copyOf(skipped));
    }

    private ObjectNode table(IntrospectedSchema.IntrospectedTable table, SchemaIntrospector introspector,
                             String tableId, Map<String, String> columnIds) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", tableId);
        // DB COMMENT ≡ 논리명(§3.2) — DB 코멘트를 논리명으로 가져오고, 없으면 물리명으로 대신한다.
        // content의 comment 필드는 DB 코멘트와 무관한 문서 설명이라 리버스에서는 채우지 않는다.
        node.put("logicalName", table.comment() != null ? table.comment() : table.name());
        node.put("physicalName", table.name());
        node.putNull("comment");

        ArrayNode columnsNode = node.putArray("columns");
        for (IntrospectedSchema.IntrospectedColumn column : table.columns()) {
            columnsNode.add(column(column, introspector, columnIds.get(column.name())));
        }

        if (table.primaryKeyColumns().isEmpty()) {
            node.putNull("primaryKey");
        } else {
            ObjectNode primaryKey = node.putObject("primaryKey");
            String name = table.primaryKeyName();
            if (name == null || name.isBlank() || MYSQL_PRIMARY.equals(name)) {
                name = "PK_" + table.name();
            }
            primaryKey.put("name", name);
            ArrayNode columnIdArray = primaryKey.putArray("columnIds");
            table.primaryKeyColumns().stream().map(columnIds::get).forEach(columnIdArray::add);
        }

        ArrayNode uniquesNode = node.putArray("uniques");
        for (IntrospectedSchema.IntrospectedUnique unique : table.uniques()) {
            ObjectNode uniqueNode = uniquesNode.addObject();
            uniqueNode.put("id", UUID.randomUUID().toString());
            uniqueNode.put("name", unique.name());
            ArrayNode columnIdArray = uniqueNode.putArray("columnIds");
            unique.columns().stream().map(columnIds::get).forEach(columnIdArray::add);
        }
        node.putArray("indexes");
        return node;
    }

    private ObjectNode column(IntrospectedSchema.IntrospectedColumn column, SchemaIntrospector introspector,
                              String columnId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", columnId);
        node.put("physicalName", column.name());
        String commonType = introspector.commonTypeCode(column.typeName());
        node.put("dataType", commonType);
        // length는 CHAR·VARCHAR에만, precision/scale은 DECIMAL에만 — 나머지는 버린다
        node.put("length", LENGTH_TYPES.contains(commonType) && column.length() != null
                ? column.length() : null);
        node.put("precision", PRECISION_TYPES.contains(commonType) && column.precision() != null
                ? column.precision() : null);
        node.put("scale", PRECISION_TYPES.contains(commonType) && column.scale() != null
                ? column.scale() : null);
        node.put("nullable", column.nullable());
        if (column.defaultValue() != null) {
            node.put("defaultValue", column.defaultValue());
        } else {
            node.putNull("defaultValue");
        }
        node.put("autoIncrement", column.autoIncrement());
        // 테이블과 같은 규칙 — DB 코멘트가 논리명이고, 없으면 물리명
        node.put("logicalName", column.comment() != null ? column.comment() : column.name());
        node.putNull("comment");
        return node;
    }

    /**
     * FK → 관계 변환 — 식별 여부·기수는 04-dbms-engineering.md 3.2 규칙.
     * 컬럼 id 매핑에 빠진 것이 하나라도 있으면 null(호출부가 skipped에 기록).
     */
    private ObjectNode relationship(IntrospectedSchema.IntrospectedFk fk, String parentTableId, String childTableId,
                                    IntrospectedSchema.IntrospectedTable childTable, Map<String, String> childColumnIds,
                                    Map<String, String> parentColumnIds) {
        List<String> childColumnIdList = new ArrayList<>();
        for (String name : fk.childColumns()) {
            String id = childColumnIds.get(name);
            if (id == null) {
                return null;
            }
            childColumnIdList.add(id);
        }
        List<String> parentColumnIdList = new ArrayList<>();
        for (String name : fk.parentColumns()) {
            String id = parentColumnIds.get(name);
            if (id == null) {
                return null;
            }
            parentColumnIdList.add(id);
        }

        // FK 컬럼의 null 허용 여부로 부모(1) 기수를 정한다
        boolean allNotNull = fk.childColumns().stream()
                .map(name -> childTable.columns().stream()
                        .filter(c -> c.name().equals(name)).findFirst().orElse(null))
                .allMatch(c -> c != null && !c.nullable());

        Set<String> primaryKeyNames = new HashSet<>(childTable.primaryKeyColumns());
        boolean fkInsidePrimaryKey = primaryKeyNames.containsAll(fk.childColumns());
        boolean fkMatchesUnique = childTable.uniques().stream()
                .anyMatch(unique -> unique.columns().size() == fk.childColumns().size()
                        && new HashSet<>(unique.columns()).containsAll(fk.childColumns()));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", UUID.randomUUID().toString());
        node.put("name", fk.name());
        node.put("parentTableId", parentTableId);
        node.put("childTableId", childTableId);
        boolean oneToOne = fkInsidePrimaryKey || fkMatchesUnique;
        node.put("type", oneToOne ? "ONE_TO_ONE" : "ONE_TO_MANY");
        node.put("identifying", fkInsidePrimaryKey);
        node.put("parentMultiplicity", allNotNull ? "EXACTLY_ONE" : "ZERO_OR_ONE");
        node.put("childMultiplicity", oneToOne
                ? (allNotNull ? "EXACTLY_ONE" : "ZERO_OR_ONE")
                : "ZERO_OR_MORE");
        node.put("fkName", fk.name());
        ArrayNode mappings = node.putArray("columnMappings");
        for (int i = 0; i < childColumnIdList.size(); i++) {
            ObjectNode mapping = mappings.addObject();
            mapping.put("parentColumnId", parentColumnIdList.get(i));
            mapping.put("childColumnId", childColumnIdList.get(i));
        }
        node.put("onDelete", referentialAction(fk.onDelete()));
        node.put("onUpdate", referentialAction(fk.onUpdate()));
        return node;
    }

    /** 카탈로그 원문("SET NULL" 등) → content enum("SET_NULL") */
    private static String referentialAction(String rule) {
        String normalized = rule == null ? "" : rule.trim().toUpperCase().replace(' ', '_');
        return switch (normalized) {
            case "CASCADE", "RESTRICT", "SET_NULL", "SET_DEFAULT" -> normalized;
            default -> "NO_ACTION";
        };
    }
}
