package net.java21.crowfoot.api.model.ddl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 기본값 DDL 표기 (v1.34 — 따옴표 없는 문자열 기본값이 DDL을 깨던 결함) */
class DefaultLiteralsTest {

    private static DdlContent.Column column(String type, String defaultValue) {
        return new DdlContent.Column("c", "c", type, null, null, null, true, defaultValue, false, null);
    }

    @Test
    @DisplayName("문자 타입의 따옴표 없는 값은 작은따옴표로 감싸고, 예약어·작은따옴표도 문자열로 낸다")
    void quotesStrings() {
        assertThat(DefaultLiterals.render(column("VARCHAR", "USER"), "mysql")).isEqualTo("'USER'");
        assertThat(DefaultLiterals.render(column("VARCHAR", "DEFAULT"), "mysql")).isEqualTo("'DEFAULT'");
        assertThat(DefaultLiterals.render(column("VARCHAR", "Asia/Seoul"), "postgres")).isEqualTo("'Asia/Seoul'");
        assertThat(DefaultLiterals.render(column("VARCHAR", "it's"), "mysql")).isEqualTo("'it''s'");
        assertThat(DefaultLiterals.render(column("VARCHAR", "0"), "mysql")).isEqualTo("'0'");
        assertThat(DefaultLiterals.render(column("VARCHAR", "CURRENT_DATE"), "mysql")).isEqualTo("'CURRENT_DATE'");
    }

    @Test
    @DisplayName("이미 감싼 값·괄호 식·함수 호출·NULL은 그대로, 시각 키워드는 날짜시간 타입에서만 그대로")
    void keepsExpressions() {
        assertThat(DefaultLiterals.render(column("VARCHAR", "'USER'"), "mysql")).isEqualTo("'USER'");
        assertThat(DefaultLiterals.render(column("CHAR", "(uuid())"), "mysql")).isEqualTo("(uuid())");
        assertThat(DefaultLiterals.render(column("UUID", "gen_random_uuid()"), "postgres")).isEqualTo("gen_random_uuid()");
        assertThat(DefaultLiterals.render(column("DATETIME", "CURRENT_TIMESTAMP(6)"), "mysql")).isEqualTo("CURRENT_TIMESTAMP(6)");
        assertThat(DefaultLiterals.render(column("TIMESTAMP", "CURRENT_TIMESTAMP"), "postgres")).isEqualTo("CURRENT_TIMESTAMP");
        assertThat(DefaultLiterals.render(column("DATE", "2026-01-01"), "mysql")).isEqualTo("'2026-01-01'");
        assertThat(DefaultLiterals.render(column("VARCHAR", "NULL"), "mysql")).isEqualTo("NULL");
        assertThat(DefaultLiterals.render(column("VARCHAR", ""), "mysql")).isNull();
    }

    @Test
    @DisplayName("숫자는 그대로, 불리언은 방언에 맞게(Oracle·SQL Server는 1/0)")
    void numbersAndBooleans() {
        assertThat(DefaultLiterals.render(column("INT", "-1"), "mysql")).isEqualTo("-1");
        assertThat(DefaultLiterals.render(column("DECIMAL", "0.5"), "postgres")).isEqualTo("0.5");
        assertThat(DefaultLiterals.render(column("BOOLEAN", "true"), "mysql")).isEqualTo("TRUE");
        assertThat(DefaultLiterals.render(column("BOOLEAN", "FALSE"), "mssql")).isEqualTo("0");
        assertThat(DefaultLiterals.render(column("BOOLEAN", "1"), "oracle")).isEqualTo("1");
    }

    @Test
    @DisplayName("비교 — 바깥 따옴표 유무만 다른 값은 같고, 불리언 컬럼은 TRUE≡1")
    void sameDefault() {
        assertThat(DefaultLiterals.sameDefault(column("VARCHAR", "USER"), column("VARCHAR", "'USER'"))).isTrue();
        assertThat(DefaultLiterals.sameDefault(column("VARCHAR", ""), column("VARCHAR", null))).isTrue();
        assertThat(DefaultLiterals.sameDefault(column("BOOLEAN", "TRUE"), column("BOOLEAN", "1"))).isTrue();
        assertThat(DefaultLiterals.sameDefault(column("VARCHAR", "A"), column("VARCHAR", "B"))).isFalse();
    }
}
