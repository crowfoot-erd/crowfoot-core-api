package net.java21.crowfoot.api.model.edit;

import java.util.List;

/**
 * 문서 편집 API의 요청 본문 (08-core/17-model-edit.md Section 3.2~3.4).
 * 주지 않은 필드(null)는 "그대로 둔다"는 뜻이라 모든 필드를 박스 타입으로 받는다. 값의 검증은 {@link DocumentEditor}가 한다.
 */
public final class EditRequests {

    private EditRequests() {
    }

    public record RequirementsApply(Long baseVersion, String note, List<RequirementItem> items) {
    }

    public record RequirementItem(String code, String title, String description, String status, String scope,
                                  String domain, List<String> tables) {
    }

    public record SchemaApply(Long baseVersion, String note, List<TableItem> tables,
                              List<RelationshipItem> relationships, List<AreaItem> areas) {
    }

    public record TableItem(String physicalName, String rename, String logicalName, String description,
                            List<ColumnItem> columns, List<String> primaryKey, List<UniqueItem> uniques,
                            List<IndexItem> indexes, List<String> requirementCodes) {
    }

    public record ColumnItem(String physicalName, String rename, String logicalName, String description, String dataType,
                             Integer length, Integer precision, Integer scale, Boolean nullable, String defaultValue,
                             Boolean autoIncrement, String domainType) {
    }

    public record UniqueItem(String name, List<String> columns) {
    }

    public record IndexItem(String name, List<IndexColumnItem> columns) {
    }

    public record IndexColumnItem(String name, String order) {
    }

    public record RelationshipItem(String parent, String child, String type, Boolean identifying,
                                   String parentMultiplicity, String childMultiplicity, String onDelete, String onUpdate,
                                   List<ColumnMappingItem> columnMappings) {
    }

    public record ColumnMappingItem(String parentColumn, String childColumn) {
    }

    public record AreaItem(String name, String rename, String color, String description, List<String> tables) {
    }

    public record SchemaRemove(Long baseVersion, String note, List<String> tables, List<ColumnRef> columns,
                               List<RelationshipRef> relationships, List<String> requirements) {
    }

    public record ColumnRef(String table, String column) {
    }

    public record RelationshipRef(String parent, String child) {
    }

    /** 워크스페이스 도메인 타입 — 컬럼 입력의 domainType 이름으로 찾는다 */
    public record DomainTypeRef(String id, String name, int version, String dataType, Integer length, Integer precision,
                                Integer scale, boolean nullable, String defaultValue) {
    }
}
