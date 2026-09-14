package net.java21.crowfoot.api.model.ddl;

import java.util.List;

/**
 * DDL 생성용 content 해석 결과 (05-editor/01-core.md Canonical 문서 v1의 model 계층).
 *
 * <p>content는 에디터가 소유하는 불투명 블롭이 원칙이지만(1.5.1), DDL 생성(§3.1)은
 * 서버가 model 계층만 읽는다 — diagram·노트 레이아웃은 생성에 불필요하다.
 * 파싱은 관대하게(빠진 필드=기본값, 물리명 없는 항목=건너뜀) — 저장 시점 검증이
 * JSON 구문만 보므로 어떤 형태의 content라도 죽지 않아야 한다.
 *
 * <p>물리 타입 표기는 저장되지 않는다 — 컬럼엔 공용 논리 타입 코드만 있고
 * 물리 표기는 생성 시점에 {@link DbmsTemplates} 매핑으로 조립된다.
 *
 * <p>DB COMMENT 규칙(§3.1): 코멘트의 원천은 문서의 **논리명**이다. content의
 * {@code comment} 필드는 DB 코멘트와 무관한 문서 설명이라 DDL에 나가지 않는다.
 * 역방향(리버스)도 DB 코멘트를 논리명으로 채워 왕복(ERD→DB→ERD)을 보존한다.
 */
public record DdlContent(List<Table> tables, List<Relationship> relationships) {

    /** 테이블 — 컬럼 순서가 DDL 컬럼 순서가 된다 */
    public record Table(
            String id,
            String physicalName,
            String logicalName,
            List<Column> columns,
            KeyConstraint primaryKey,
            List<KeyConstraint> uniques,
            List<Index> indexes) {
    }

    /** 컬럼 — length는 CHAR·VARCHAR, precision/scale은 DECIMAL에만 의미가 있다 */
    public record Column(
            String id,
            String physicalName,
            String dataType,
            Integer length,
            Integer precision,
            Integer scale,
            boolean nullable,
            String defaultValue,
            boolean autoIncrement,
            String logicalName) {
    }

    /** PK·UK 제약 — 이름은 문서 전체 단일 네임스페이스(§18)라 DDL에서 충돌하지 않는다 */
    public record KeyConstraint(String name, List<String> columnIds) {
    }

    /** 인덱스 — 컬럼별 정렬 포함 */
    public record Index(String name, List<IndexColumn> columns) {
    }

    public record IndexColumn(String columnId, String order) {
    }

    /** 관계 = FK — parent가 참조되는(1쪽) 테이블, child가 FK 컬럼을 소유한(N쪽) 테이블 */
    public record Relationship(
            String fkName,
            String parentTableId,
            String childTableId,
            List<ColumnMapping> columnMappings,
            String onDelete,
            String onUpdate) {
    }

    /** FK 컬럼 매핑 — 순서 = 부모 PK 정의 순서(에디터 relationship 빌더 규칙) */
    public record ColumnMapping(String parentColumnId, String childColumnId) {
    }
}
