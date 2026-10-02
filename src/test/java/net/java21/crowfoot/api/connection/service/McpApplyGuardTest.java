package net.java21.crowfoot.api.connection.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;
import net.java21.crowfoot.api.auth.CurrentUser;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.managed.domain.ManagedDatabase;
import net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository;
import net.java21.crowfoot.common.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** MCP 반영 허용 판정 (08-core/06-connection.md Section 2.1) */
@ExtendWith(MockitoExtension.class)
class McpApplyGuardTest {

    @Mock
    private ManagedDatabaseRepository managedDatabaseRepository;
    @InjectMocks
    private McpApplyGuard guard;

    @AfterEach
    void clear() {
        CurrentUserHolder.clear();
    }

    private static DbConnection connection(boolean allowed) {
        DbConnection connection = new DbConnection(77L, "개발 PG", "postgresql", "db", 5432, "shop", null, "app", new byte[0], 2L);
        ReflectionTestUtils.setField(connection, "id", 9L);
        connection.setMcpApplyAllowed(allowed);
        return connection;
    }

    @Test
    @DisplayName("웹에서 로그인한 사용자의 요청은 설정과 무관하다")
    void webRequest() {
        CurrentUserHolder.set(new CurrentUser(2L));
        assertThatCode(() -> guard.requireAllowed(connection(false))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("토큰으로 온 요청 — 설정이 꺼진 사용자 커넥션은 거부, 켜진 커넥션과 매니지드는 허용")
    void tokenRequest() {
        CurrentUserHolder.set(new CurrentUser(2L, 77L, 12L));
        given(managedDatabaseRepository.findByConnectionId(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireAllowed(connection(false))).isInstanceOf(BusinessException.class);
        assertThatCode(() -> guard.requireAllowed(connection(true))).doesNotThrowAnyException();

        given(managedDatabaseRepository.findByConnectionId(9L)).willReturn(Optional.of(Mockito.mock(ManagedDatabase.class)));
        assertThatCode(() -> guard.requireAllowed(connection(false))).doesNotThrowAnyException();
    }
}
