package net.java21.crowfoot.api.internal.service;

import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.internal.dto.ConnectionAccessResponse;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DB 매니저용 접근 확인 단위 테스트 (08-core/15-internal-api.md Section 2.1) —
 * 판정 순서(비멤버 404 → 커넥션 없음 404 → Editor 미만 403)와 통과 시 접속 정보 매핑을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class InternalConnectionAccessServiceTest {

    private static final long USER = 7L;
    private static final long WORKSPACE = 34L;
    private static final long CONNECTION = 302L;
    private static final byte[] CIPHER = "cipher".getBytes(StandardCharsets.UTF_8);

    @Mock
    private RoleChecker roleChecker;
    @Mock
    private DbConnectionRepository connectionRepository;
    @Mock
    private ConnectionCrypto crypto;

    @InjectMocks
    private InternalConnectionAccessService service;

    private static DbConnection connection() {
        DbConnection connection = new DbConnection(WORKSPACE, "개발 MySQL", "mysql", "db.dev.example.com", 3306,
                "shop", null, "crowfoot", CIPHER, USER);
        ReflectionTestUtils.setField(connection, "id", CONNECTION);
        return connection;
    }

    @Test
    @DisplayName("통과 — Editor 이상이면 복호화한 접속 정보와 유효 역할을 돌려준다")
    void returnsDecryptedAccessForEditor() {
        given(roleChecker.requireMember(USER, WORKSPACE)).willReturn(new EffectiveRole("EDITOR", 50));
        given(connectionRepository.findByIdAndWorkspaceId(CONNECTION, WORKSPACE)).willReturn(Optional.of(connection()));
        given(roleChecker.requireEditor(USER, WORKSPACE)).willReturn(new EffectiveRole("EDITOR", 50));
        given(crypto.decrypt(CIPHER)).willReturn("plain-pw");

        ConnectionAccessResponse response = service.access(CONNECTION, USER, WORKSPACE);

        assertThat(response.connectionId()).isEqualTo("302");
        assertThat(response.workspaceId()).isEqualTo("34");
        assertThat(response.role()).isEqualTo("EDITOR");
        assertThat(response.dbmsType()).isEqualTo("mysql");
        assertThat(response.host()).isEqualTo("db.dev.example.com");
        assertThat(response.port()).isEqualTo(3306);
        assertThat(response.databaseName()).isEqualTo("shop");
        assertThat(response.schemaName()).isNull();
        assertThat(response.username()).isEqualTo("crowfoot");
        assertThat(response.password()).isEqualTo("plain-pw");
        // 로그에 값이 찍히지 않게 toString은 비밀번호를 가린다
        assertThat(response.toString()).doesNotContain("plain-pw").contains("password=***");
    }

    @Test
    @DisplayName("비멤버 — 존재 은닉 404 WORKSPACE_NOT_FOUND, 커넥션을 조회하지 않는다")
    void hidesWorkspaceFromNonMember() {
        given(roleChecker.requireMember(USER, WORKSPACE)).willThrow(new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND));

        assertThatThrownBy(() -> service.access(CONNECTION, USER, WORKSPACE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
        verify(connectionRepository, never()).findByIdAndWorkspaceId(CONNECTION, WORKSPACE);
        verify(crypto, never()).decrypt(any());
    }

    @Test
    @DisplayName("커넥션이 없거나 다른 워크스페이스 소속 — 404 CONNECTION_NOT_FOUND")
    void notFoundWhenConnectionIsNotInWorkspace() {
        given(roleChecker.requireMember(USER, WORKSPACE)).willReturn(new EffectiveRole("OWNER", 100));
        given(connectionRepository.findByIdAndWorkspaceId(CONNECTION, WORKSPACE)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.access(CONNECTION, USER, WORKSPACE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_FOUND));
        verify(crypto, never()).decrypt(any());
    }

    @Test
    @DisplayName("Editor 미만(Viewer·Commenter) — 403 PERMISSION_DENIED, 복호화하지 않는다")
    void deniesBelowEditor() {
        given(roleChecker.requireMember(USER, WORKSPACE)).willReturn(new EffectiveRole("VIEWER", 10));
        given(connectionRepository.findByIdAndWorkspaceId(CONNECTION, WORKSPACE)).willReturn(Optional.of(connection()));
        given(roleChecker.requireEditor(USER, WORKSPACE)).willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED));

        assertThatThrownBy(() -> service.access(CONNECTION, USER, WORKSPACE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        verify(crypto, never()).decrypt(any());
    }
}
