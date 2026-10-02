package net.java21.crowfoot.api.auth;

import static net.java21.crowfoot.api.auth.TokenScope.Decision.ALLOW;
import static net.java21.crowfoot.api.auth.TokenScope.Decision.FORBIDDEN;
import static net.java21.crowfoot.api.auth.TokenScope.Decision.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 토큰으로 온 요청의 범위 판정 (08-core/18-access-token.md Section 4) — 토큰은 워크스페이스 77에 묶여 있다 */
class TokenScopeTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @DisplayName("허용 — 그 워크스페이스의 문서·사전·도메인 타입 읽기와 쓰기, 편집 API, 배포, 매니지드 발급")
    @CsvSource({
            "GET, /core/database-types",
            "GET, /core/workspaces/77",
            "GET, /core/workspaces/77/models",
            "POST, /core/workspaces/77/models",
            "POST, /core/workspaces/77/models/sql-import",
            "GET, /core/workspaces/77/models/501/outline",
            "POST, /core/workspaces/77/models/501/schema/apply",
            "POST, /core/workspaces/77/models/501/schema/remove",
            "GET, /core/workspaces/77/models/501/ddl",
            "POST, /core/workspaces/77/models/501/deploy",
            "POST, /core/workspaces/77/models/501/connections",
            "GET, /core/workspaces/77/models/501/connections/9/migration",
            "POST, /core/workspaces/77/models/501/connections/9/migration/execute",
            "GET, /core/workspaces/77/connections",
            "GET, /core/workspaces/77/managed-databases",
            "POST, /core/workspaces/77/managed-databases",
            "GET, /core/workspaces/77/terms",
            "GET, /core/workspaces/77/domain-types",
            "GET, /core/workspaces/77/memberships",
    })
    void allowed(String method, String path) {
        assertThat(TokenScope.decide(method, path, 77)).isEqualTo(ALLOW);
    }

    @ParameterizedTest(name = "{0} {1}")
    @DisplayName("다른 워크스페이스는 없는 것처럼 숨긴다")
    @CsvSource({
            "GET, /core/workspaces/78",
            "GET, /core/workspaces/78/models",
            "POST, /core/workspaces/7/models/501/schema/apply",
            "GET, /core/workspaces/770/models",
    })
    void otherWorkspace(String method, String path) {
        assertThat(TokenScope.decide(method, path, 77)).isEqualTo(NOT_FOUND);
    }

    @ParameterizedTest(name = "{0} {1}")
    @DisplayName("막는 것 — 워크스페이스 밖의 경로와, 사람이 화면에서 해야 하는 일")
    @CsvSource({
            "GET, /core/accounts/me",
            "GET, /core/accounts/me/workspaces",
            "GET, /core/teams",
            "GET, /core/admin/users",
            "GET, /core/community/posts",
            "POST, /core/database-types",
            "POST, /core/workspaces",
            "GET, /core/workspaces/77/access-tokens",
            "POST, /core/workspaces/77/access-tokens",
            "DELETE, /core/workspaces/77/access-tokens/3",
            "PATCH, /core/workspaces/77",
            "DELETE, /core/workspaces/77",
            "POST, /core/workspaces/77/memberships",
            "DELETE, /core/workspaces/77/memberships/4",
            "DELETE, /core/workspaces/77/models/501",
            "POST, /core/workspaces/77/connections",
            "PATCH, /core/workspaces/77/connections/9",
            "DELETE, /core/workspaces/77/connections/9",
            "DELETE, /core/workspaces/77/managed-databases/5",
            "GET, /core/workspaces/77/managed-databases/5/credential",
            "POST, /core/workspaces/77/models/501/shares",
            "DELETE, /core/workspaces/77/models/501/shares/8",
    })
    void forbidden(String method, String path) {
        assertThat(TokenScope.decide(method, path, 77)).isEqualTo(FORBIDDEN);
    }
}
