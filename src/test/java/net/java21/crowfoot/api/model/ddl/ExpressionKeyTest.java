package net.java21.crowfoot.api.model.ddl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 식 비교 키 — DBMS가 다시 쓴 식과 문서의 식을 같은 것으로 본다 (v1.35 — MCP E2E 하네스가 찾은 결함) */
class ExpressionKeyTest {

    @Test
    @DisplayName("PostgreSQL이 다시 쓴 IN 목록·캐스트를 문서의 식과 같은 것으로 본다")
    void postgresRewrites() {
        assertThat(SchemaDiffer.expressionKey(
                "((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'BLOCKED'::character varying])::text[]))"))
                .isEqualTo(SchemaDiffer.expressionKey("status IN ('ACTIVE', 'BLOCKED')"));
        assertThat(SchemaDiffer.expressionKey("(qty)::numeric * price"))
                .isEqualTo(SchemaDiffer.expressionKey("qty * price"));
        assertThat(SchemaDiffer.expressionKey("((max_blogs IS NULL) OR (max_blogs >= 0))"))
                .isEqualTo(SchemaDiffer.expressionKey("max_blogs IS NULL OR max_blogs >= 0"));
    }

    @Test
    @DisplayName("MySQL이 덧붙인 백틱·괄호를 무시하고, 값이 다르면 다르다")
    void mysqlAndRealChanges() {
        assertThat(SchemaDiffer.expressionKey("(`max_posts` >= 0)")).isEqualTo(SchemaDiffer.expressionKey("max_posts >= 0"));
        assertThat(SchemaDiffer.expressionKey("status IN ('ACTIVE')"))
                .isNotEqualTo(SchemaDiffer.expressionKey("status IN ('ACTIVE', 'BLOCKED')"));
    }
}
