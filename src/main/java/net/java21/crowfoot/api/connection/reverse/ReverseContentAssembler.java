package net.java21.crowfoot.api.connection.reverse;

import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
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
import java.util.stream.Collectors;

/**
 * Introspection 결과 → Canonical content v1 조립 (05-editor/04-dbms-engineering.md Section 3.2).
 *
 * <p>정방향(DbmsTemplates)과 같은 원천의 역방향 매핑: 물리 타입은 introspector의
 * {@link SchemaIntrospector#commonTypeCode}로 공용 논리 코드로 바꾼다. 논리명은 DB 코멘트
 * (§3.2 규칙 — DB COMMENT ≡ 논리명, 없으면 물리명), 배치는 그리드(4열) 초기 좌표를 준다 —
 * 사용자가 elkjs 자동 배치로 다시 잡을 수 있다.
 *
 * <p>키 이름은 문서 전체 단일 네임스페이스(01-core.md Section 18)라 DB 제약 이름을 그대로
 * 쓰되, MySQL PK 상수명({@code PRIMARY})은 에디터 기본 이름 {@code {테이블}_pk}로 정규화한다.
 *
 * <p>FK 인덱스 자동 생성 (05-editor/01-core.md §6.6 인덱스 자동 생성 정책) — PostgreSQL·Oracle·
 * SQL Server 등 FK 선언만으로 자식 인덱스를 만들지 않는 DBMS는 조립 시점에 FK 전체 컬럼으로
 * 복합 인덱스({@code idx_{테이블}_{컬럼…}})를 만든다. MySQL(InnoDB)은 FK 제약 생성 시 DB가
 * 알아서 만들므로 ERD에 만들지 않는다. PK·UK의 백킹 인덱스는 모든 DBMS가 자동 생성하므로
 * 별도 Index 객체로 만들지 않는다 — FK 선두 컬럼이 PK·UK 선두로 덮이면 자동 인덱스도 건너뛴다.
 */
@Component
public class ReverseContentAssembler {

    /** 그리드 배치 — 열 4개, 테이블 폭 여유를 감안한 간격 */
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_X_GAP = 360;
    private static final int GRID_Y_GAP = 320;
    private static final int GRID_MARGIN = 80;

    /** 길이(n)를 저장하는 공용 코드 — 나머지 타입의 length는 버린다 (dbms.ts 규칙과 동일) */
    private static final Set<String> LENGTH_TYPES = Set.of("CHAR", "VARCHAR", "BINARY", "VARBINARY");

    /** 정밀도(p,s)를 저장하는 공용 코드 */
    private static final Set<String> PRECISION_TYPES = Set.of("DECIMAL");

    /** 소수 초 자릿수를 precision에 저장하는 공용 코드 */
    private static final Set<String> FRACTIONAL_TYPES = Set.of("TIME", "DATETIME", "TIMESTAMP");

    /** MySQL PK 제약의 상수 이름 — 모든 테이블이 같아서 단일 네임스페이스 규칙 위반 */
    private static final String MYSQL_PRIMARY = "PRIMARY";

    /** FK 선언만으로 자식 컬럼 인덱스를 자동 생성하는 DBMS — 이 문서는 FK 인덱스를 ERD에 만들지 않는다 */
    private static final String FK_AUTO_INDEX_DBMS = "mysql";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 조립 결과 — content JSON 문자열과 리버스 요약(08-core/06-connection.md Section 3.6) */
    public record AssembledContent(String content, int tableCount, int relationshipCount, List<String> skipped) {
    }

    public AssembledContent assemble(IntrospectedSchema schema, SchemaIntrospector introspector, String databaseType) {
        List<String> skipped = new ArrayList<>();
        boolean fkIndexesManagedByDb = databaseType != null
                && FK_AUTO_INDEX_DBMS.equalsIgnoreCase(databaseType.trim());

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
        Map<String, ObjectNode> tableNodes = new HashMap<>();
        Set<String> usedKeyNames = new HashSet<>();
        for (IntrospectedSchema.IntrospectedTable table : schema.tables()) {
            ObjectNode tableNode = table(table, introspector, tableIds.get(table.name()), columnIds.get(table.name()));
            tablesNode.add(tableNode);
            tableNodes.put(table.name(), tableNode);
            if (tableNode.hasNonNull("primaryKey")) {
                usedKeyNames.add(tableNode.get("primaryKey").get("name").asText().toLowerCase());
            }
            tableNode.get("uniques").forEach(u -> usedKeyNames.add(u.get("name").asText().toLowerCase()));
            tableNode.get("indexes").forEach(i -> usedKeyNames.add(i.get("name").asText().toLowerCase()));
            tableNode.get("checks").forEach(c -> usedKeyNames.add(c.get("name").asText().toLowerCase()));
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
            // FK 인덱스 자동 생성(§6.6) — DB가 알아서 만드는 DBMS는 건너뛰고, 선두 컬럼이
            // PK·UK 선두로 덮이는 FK도 건너뛴다(키 백킹 인덱스가 이미 커버)
            if (!fkIndexesManagedByDb) {
                addFkIndexIfNeeded(tableNodes.get(fk.childTable()), fk,
                        columnIds.get(fk.childTable()), usedKeyNames);
            }
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
        node.put("logicalName", table.comment() != null ? CommentLogicalName.of(table.comment()) : table.name());
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
                name = table.name().toLowerCase(java.util.Locale.ROOT) + "_pk"; // 에디터 기본 이름과 같다
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
        ArrayNode indexesNode = node.putArray("indexes");
        for (IntrospectedSchema.IntrospectedIndex index : table.indexes()) {
            List<String> ids = new ArrayList<>();
            for (IntrospectedSchema.IndexColumn column : index.columns()) {
                ids.add(columnIds.get(column.name()));
            }
            if (ids.isEmpty() || ids.contains(null)) {
                continue; // 식 인덱스처럼 컬럼으로 표현하지 못하는 인덱스
            }
            ObjectNode indexNode = indexesNode.addObject();
            indexNode.put("id", UUID.randomUUID().toString());
            indexNode.put("name", index.name());
            ArrayNode columns = indexNode.putArray("columns");
            for (int i = 0; i < ids.size(); i++) {
                String order = index.columns().get(i).order();
                columns.addObject().put("columnId", ids.get(i)).put("order", "DESC".equalsIgnoreCase(order) ? "DESC" : "ASC");
            }
            indexNode.put("type", index.type() == null ? "BTREE" : index.type());
            if (index.parser() == null) {
                indexNode.putNull("parser");
            } else {
                indexNode.put("parser", index.parser());
            }
        }
        ArrayNode checksNode = node.putArray("checks");
        for (IntrospectedSchema.IntrospectedCheck check : table.checks()) {
            ObjectNode checkNode = checksNode.addObject();
            checkNode.put("id", UUID.randomUUID().toString());
            checkNode.put("name", check.name());
            checkNode.put("expression", check.expression());
        }
        return node;
    }

    /**
     * FK 인덱스 자동 생성 (§6.6) — FK 전체 컬럼으로 복합 인덱스를 자식 테이블에 추가한다.
     * 이름은 에디터 생성 기본값과 같은 {@code idx_{테이블}_{컬럼…}}(소문자). 선두 자식 컬럼이
     * PK·UK·이미 만든 인덱스의 선두 컬럼이면 건너뛴다(키 백킹 인덱스가 접두를 이미 커버).
     */
    private void addFkIndexIfNeeded(ObjectNode childTable, IntrospectedSchema.IntrospectedFk fk,
                                    Map<String, String> childColumnIds, Set<String> usedKeyNames) {
        List<String> columnIdList = new ArrayList<>();
        for (String name : fk.childColumns()) {
            String id = childColumnIds.get(name);
            if (id == null) return; // 관계는 만들어졌지만 컬럼을 못 찾으면 인덱스도 만들지 않는다
            columnIdList.add(id);
        }
        String leading = columnIdList.get(0);
        if (childTable.hasNonNull("primaryKey")) {
            if (leading.equals(childTable.get("primaryKey").get("columnIds").get(0).asText())) return;
        }
        for (JsonNode unique : childTable.get("uniques")) {
            if (leading.equals(unique.get("columnIds").get(0).asText())) return;
        }
        for (JsonNode index : childTable.get("indexes")) {
            if (leading.equals(index.get("columns").get(0).get("columnId").asText())) return;
        }

        String tableName = childTable.get("physicalName").asText().toLowerCase();
        String base = "idx_" + tableName + "_" + fk.childColumns().stream()
                .map(name -> name.toLowerCase()).collect(Collectors.joining("_"));
        String name = base;
        int suffix = 2;
        while (!usedKeyNames.add(name)) {
            name = base + "_" + suffix++;
        }

        ObjectNode index = ((ArrayNode) childTable.get("indexes")).addObject(); // 조립 직후라 항상 빈 배열
        index.put("id", UUID.randomUUID().toString());
        index.put("name", name);
        ArrayNode columns = index.putArray("columns");
        for (String columnId : columnIdList) {
            columns.addObject().put("columnId", columnId).put("order", "ASC");
        }
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
        boolean keepsPrecision = PRECISION_TYPES.contains(commonType)
                || (FRACTIONAL_TYPES.contains(commonType) && column.precision() != null && column.precision() > 0);
        node.put("precision", keepsPrecision && column.precision() != null ? column.precision() : null);
        node.put("scale", PRECISION_TYPES.contains(commonType) && column.scale() != null
                ? column.scale() : null);
        node.put("nullable", column.nullable());
        if (column.generatedExpression() != null) {
            node.putObject("generated")
                    .put("expression", column.generatedExpression())
                    .put("stored", column.generatedStored());
        } else {
            node.putNull("generated");
        }
        if (column.onUpdate() != null && column.generatedExpression() == null) {
            node.put("onUpdate", column.onUpdate());
        } else {
            node.putNull("onUpdate");
        }
        if (column.defaultValue() != null && column.generatedExpression() == null) {
            node.put("defaultValue", column.defaultValue());
        } else {
            node.putNull("defaultValue");
        }
        node.put("autoIncrement", column.autoIncrement());
        // 테이블과 같은 규칙 — DB 코멘트가 논리명이고, 없으면 물리명
        node.put("logicalName", column.comment() != null ? CommentLogicalName.of(column.comment()) : column.name());
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
        // 1:N 자식 기수(부모 행마다 자식이 몇 개인가)는 DDL·스키마 어느 쪽으로도 증명되지 않는다 —
        // 에디터 관계 생성 기본값(양쪽 모두 필수, ONE_OR_MORE)과 일관되게 둔다(#279). 임포트 결과가
        // 수동 생성 문서와 다른 표기(○< vs |<)로 보이는 것이 사용자에게 버그로 읽혔던 원인.
        node.put("childMultiplicity", oneToOne
                ? (allNotNull ? "EXACTLY_ONE" : "ZERO_OR_ONE")
                : "ONE_OR_MORE");
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
