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

    /** 요구사항 동기화 계획·적용 (Section 3.5 — v1.36). 적용은 planFingerprint가 필요하다 */
    public record RequirementsSync(String note, List<RequirementItem> items, Boolean acceptRemovals, String planFingerprint) {
    }

    public record RequirementItem(String code, String title, String description, String status, String scope,
                                  String domain, List<String> tables, List<CriterionItem> criteria) {

        public RequirementItem(String code, String title, String description, String status, String scope,
                               String domain, List<String> tables) {
            this(code, title, description, status, scope, domain, tables, null);
        }
    }

    /** 수용 기준 한 줄(v1.36) — sql은 데이터로 확인하는 SELECT(한 값), expect는 기대값(생략하면 "0") */
    public record CriterionItem(String text, String sql, String expect) {
    }

    public record SchemaApply(Long baseVersion, String note, List<TableItem> tables,
                              List<RelationshipItem> relationships, List<AreaItem> areas) {
    }

    public record TableItem(String physicalName, String rename, String logicalName, String description,
                            List<ColumnItem> columns, List<String> primaryKey, List<UniqueItem> uniques,
                            List<IndexItem> indexes, List<String> requirementCodes, List<CheckItem> checks) {

        /** CHECK 없는 요청 — v1.34 이전 꼴 */
        public TableItem(String physicalName, String rename, String logicalName, String description,
                         List<ColumnItem> columns, List<String> primaryKey, List<UniqueItem> uniques,
                         List<IndexItem> indexes, List<String> requirementCodes) {
            this(physicalName, rename, logicalName, description, columns, primaryKey, uniques, indexes,
                    requirementCodes, null);
        }
    }

    public record ColumnItem(String physicalName, String rename, String logicalName, String description, String dataType,
                             Integer length, Integer precision, Integer scale, Boolean nullable, String defaultValue,
                             Boolean autoIncrement, String domainType, GeneratedItem generated, String onUpdate) {

        /** 생성식·ON UPDATE 없는 요청 — v1.34 이전 꼴 */
        public ColumnItem(String physicalName, String rename, String logicalName, String description, String dataType,
                          Integer length, Integer precision, Integer scale, Boolean nullable, String defaultValue,
                          Boolean autoIncrement, String domainType) {
            this(physicalName, rename, logicalName, description, dataType, length, precision, scale, nullable,
                    defaultValue, autoIncrement, domainType, null, null);
        }
    }

    /** 생성 컬럼 — expression이 빈 문자열이면 생성 컬럼을 해제한다. stored 생략은 true */
    public record GeneratedItem(String expression, Boolean stored) {
    }

    /** CHECK 제약 — 같은 이름이 있으면 식을 바꾸고, 이름을 생략하면 ck_{테이블}_{n} */
    public record CheckItem(String name, String expression) {
    }

    public record UniqueItem(String name, List<String> columns) {
    }

    public record IndexItem(String name, List<IndexColumnItem> columns, String type, String parser) {

        /** 일반 인덱스 — v1.34 이전 꼴 */
        public IndexItem(String name, List<IndexColumnItem> columns) {
            this(name, columns, null, null);
        }
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
