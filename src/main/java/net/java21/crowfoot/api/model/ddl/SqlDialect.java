package net.java21.crowfoot.api.model.ddl;

import java.util.List;

/**
 * SQL 방언(Dialect) — DBMS별로 달라지는 DDL 표기만 담는 전략 (05-editor/04-dbms-engineering.md §1·§3.1).
 *
 * <p>문서 조립 골격(CREATE → ALTER FK → INDEX → COMMENT 순서, 들여쓰기·쉼표 규칙)은
 * {@link DdlGenerator}가 담당하고(템플릿 메서드), 각 방언은 차이 지점(타입 조립·AI 표기·
 * 코멘트 문법)만 구현한다. 신규 DBMS 지원은 이 인터페이스 구현 1개를
 * {@link Dialects} 레지스트리에 추가하는 것으로 끝난다.
 */
public interface SqlDialect {

    /** 제약 종류 코드 — {@link #dropConstraint}의 kind 값 (PK·UK·FK) */
    String KIND_PRIMARY = "PRIMARY";
    String KIND_UNIQUE = "UNIQUE";
    String KIND_FOREIGN_KEY = "FOREIGN KEY";

    String id();

    /** 컬럼 물리 타입 표기 — 템플릿 매핑 + length/precision/scale 조립 */
    String columnType(DdlContent.Column column);

    /** 자동 증가 컬럼 정의에 인라인으로 붙는 표기. 이 방언이 인라인 실현을 안 쓰면 null */
    String autoIncrementInline(DdlContent.Column column);

    /** 컬럼 정의 뒤에 붙는 코멘트 조각(소스=논리명) — MySQL {@code COMMENT '...'} / 줄 주석 방언 {@code -- ...}.
     *  {@code --}로 시작하면 조립기가 쉼표 뒤에 배치한다(주석이 쉼표를 삼키지 않게). 없으면 "" */
    String columnComment(DdlContent.Column column);

    /** CREATE TABLE 앞에 놓는 줄(테이블 코멘트 줄 주석 등). 없으면 null */
    String createTablePrefix(DdlContent.Table table);

    /** CREATE TABLE 닫는 괄호 뒤 테이블 옵션(MySQL {@code  COMMENT='...'} 등). 없으면 "" */
    String tableOption(DdlContent.Table table);

    /** CREATE 이후 별도 코멘트 문장들(PG·Oracle COMMENT ON). 없으면 빈 목록 */
    List<String> commentStatements(DdlContent.Table table);

    /** 인덱스 생성문(세미콜론 없이) */
    String createIndex(DdlContent.Table table, DdlContent.Index index);

    /* ---------- 마이그레이션 DDL(§3.3) — ALTER 계열 훅. 세미콜론 포함 ---------- */

    /** 컬럼 추가 — CREATE 정의와 같은 속성 순서(NOT NULL → DEFAULT → AI) */
    String addColumn(DdlContent.Table table, DdlContent.Column column);

    /** 컬럼 변경(타입·NULL·기본값·AI) — MySQL은 전체 재정의, PG는 절 조합, 그 외는 ALTER COLUMN.
     *  반영 못 하는 변경(예: SQL Server 기본값)은 문장에서 빠지고 생성기가 경고를 붙인다 */
    String alterColumn(DdlContent.Table table, DdlContent.Column before, DdlContent.Column after);

    /** 컬럼 삭제 */
    String dropColumn(DdlContent.Table table, DdlContent.Column column);

    /** 제약(PK·UK·FK) 삭제 — kind는 {@link #KIND_PRIMARY}·{@link #KIND_UNIQUE}·{@link #KIND_FOREIGN_KEY}.
     *  MySQL은 UK를 인덱스로, PK를 이름 없는 상수 제약으로 실현한다 */
    String dropConstraint(DdlContent.Table table, String name, String kind);

    /** 인덱스 삭제 — MySQL·SQL Server는 ON 절이 필요하다 */
    String dropIndex(DdlContent.Table table, DdlContent.Index index);

    /** 코멘트 갱신 문장들(세미콜론 없이) — 논리명이 바뀐 테이블·컬럼 대상.
     *  column이 null이면 테이블 코멘트. 줄 주석 방언(common·mssql)은 갱신 문장이 없다(빈 목록) */
    List<String> commentRefresh(DdlContent.Table table, DdlContent.Column column);
}
