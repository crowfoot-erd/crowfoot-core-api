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

    @Mock
    private net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository managedDatabaseRepository;
    @Mock
    private net.java21.crowfoot.api.managed.repository.ManagedInstanceRepository managedInstanceRepository;

    private InternalConnectionAccessService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = service("public");
    }

    private InternalConnectionAccessService service(String serverAddress) {
        return new InternalConnectionAccessService(roleChecker, connectionRepository, crypto, managedDatabaseRepository,
                new net.java21.crowfoot.api.connection.service.ConnectionEndpointResolver(
                        managedDatabaseRepository, managedInstanceRepository, serverAddress));
    }

    private void passEditor() {
        given(roleChecker.requireMember(USER, WORKSPACE)).willReturn(new EffectiveRole("EDITOR", 50));
        given(connectionRepository.findByIdAndWorkspaceId(CONNECTION, WORKSPACE)).willReturn(Optional.of(connection()));
        given(roleChecker.requireEditor(USER, WORKSPACE)).willReturn(new EffectiveRole("EDITOR", 50));
    }

    private void managed() {
        net.java21.crowfoot.api.managed.domain.ManagedDatabase database =
                new net.java21.crowfoot.api.managed.domain.ManagedDatabase(7L, USER, WORKSPACE, "cf_u2_d1", CONNECTION);
        given(managedDatabaseRepository.findByConnectionId(CONNECTION)).willReturn(Optional.of(database));
        org.mockito.Mockito.lenient().when(managedInstanceRepository.findById(7L)).thenReturn(Optional.of(
                new net.java21.crowfoot.api.managed.domain.ManagedInstance("MySQL", "mysql", "10.0.0.14", "db.public.example.com",
                        13306, null, "root", CIPHER, true, USER)));
    }

    @Test
    @DisplayName("매니지드 커넥션 — 운영 설정(internal)에서는 인스턴스의 내부 주소를, 그 밖에는 커넥션에 적힌 주소를 돌려준다")
    void managedEndpointByEnvironment() {
        passEditor();
        managed();
        given(crypto.decrypt(CIPHER)).willReturn("plain-pw");

        ConnectionAccessResponse production = service("internal").access(CONNECTION, USER, WORKSPACE, false);
        assertThat(production.host()).isEqualTo("10.0.0.14");
        assertThat(production.port()).isEqualTo(13306);

        ConnectionAccessResponse local = service("public").access(CONNECTION, USER, WORKSPACE, false);
        assertThat(local.host()).isEqualTo("db.dev.example.com");
        assertThat(local.port()).isEqualTo(3306);
    }

    @Test
    @DisplayName("사용자가 등록한 커넥션 — 운영 설정에서도 적힌 주소를 그대로 쓴다")
    void userConnectionKeepsWrittenAddress() {
        passEditor();
        given(managedDatabaseRepository.findByConnectionId(CONNECTION)).willReturn(Optional.empty());
        given(crypto.decrypt(CIPHER)).willReturn("plain-pw");

        assertThat(service("internal").access(CONNECTION, USER, WORKSPACE, false).host()).isEqualTo("db.dev.example.com");
    }

    @Test
    @DisplayName("MCP 쓰기 — 허용하지 않은 커넥션은 403, 허용한 커넥션과 매니지드 커넥션은 통과한다")
    void mcpWriteNeedsAllowedConnection() {
        passEditor();
        given(managedDatabaseRepository.findByConnectionId(CONNECTION)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.access(CONNECTION, USER, WORKSPACE, true))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));

        DbConnection allowed = connection();
        allowed.setMcpApplyAllowed(true);
        given(connectionRepository.findByIdAndWorkspaceId(CONNECTION, WORKSPACE)).willReturn(Optional.of(allowed));
        given(crypto.decrypt(CIPHER)).willReturn("plain-pw");
        assertThat(service.access(CONNECTION, USER, WORKSPACE, true).connectionId()).isEqualTo("302");

        given(connectionRepository.findByIdAndWorkspaceId(CONNECTION, WORKSPACE)).willReturn(Optional.of(connection()));
        managed();
        assertThat(service.access(CONNECTION, USER, WORKSPACE, true).connectionId()).isEqualTo("302");
    }

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

        ConnectionAccessResponse response = service.access(CONNECTION, USER, WORKSPACE, false);

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

        assertThatThrownBy(() -> service.access(CONNECTION, USER, WORKSPACE, false))
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

        assertThatThrownBy(() -> service.access(CONNECTION, USER, WORKSPACE, false))
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

        assertThatThrownBy(() -> service.access(CONNECTION, USER, WORKSPACE, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        verify(crypto, never()).decrypt(any());
    }
}
