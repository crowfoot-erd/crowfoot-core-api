package net.java21.crowfoot.api.connection.introspect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 기본값 표기 정리 (05-editor/04-dbms-engineering.md Section 3.2, 커뮤니티 신고 50) — column_default의 캐스트를
 * 떼고 문자열 상수의 따옴표를 벗겨, 배포 DDL에 쓴 표기로 돌아오는지 본다. 함수 인자 안 캐스트를 지울 때 바깥 닫는 괄호를
 * 지우지 않는지(신고 50의 to_char), 뜻이 있는 타입 캐스트는 남기는지 본다.
 */
class PgDefaultNormalizerTest {

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("column_default를 문서 표기로 되돌린다")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "'member'::text | member",
            "'[]'::text | []",
            "'insight'::character varying | insight",
            "'it''s'::text | it's",
            "''::text | ''",
            "'0'::numeric | 0",
            "'-1'::integer | -1",
            "'2026-01-01'::date | 2026-01-01",
            "'{}'::jsonb | {}",
            "\"to_char(LOCALTIMESTAMP, 'YYYY-MM-DD HH24:MI:SS'::text)\" | \"to_char(LOCALTIMESTAMP, 'YYYY-MM-DD HH24:MI:SS')\"",
            "now() | now()",
            "CURRENT_TIMESTAMP | CURRENT_TIMESTAMP",
            "LOCALTIMESTAMP(0) | LOCALTIMESTAMP(0)",
            "gen_random_uuid() | gen_random_uuid()",
            "true | true",
            "42 | 42",
            "(now() + '1 day'::interval) | (now() + '1 day'::interval)",
            "\"('a'::text || 'b'::text)\" | \"('a' || 'b')\"",
            "\"lower('X'::character varying(10)::text)\" | \"lower('X')\"",
            "\"'a::text'::text\" | \"a::text\"",
            "'ACTIVE'::character varying(20) | ACTIVE",
            "\"ARRAY['a'::text, 'b'::text]\" | \"ARRAY['a', 'b']\"",
    })
    void normalize(String raw, String expected) {
        assertThat(PgDefaultNormalizer.normalize(raw)).isEqualTo(expected);
    }
}
