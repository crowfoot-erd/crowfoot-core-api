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
}
