package net.java21.crowfoot.api.managed.service;

import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.managed.domain.ManagedDatabase;
import net.java21.crowfoot.api.managed.domain.ManagedInstance;
import net.java21.crowfoot.api.managed.domain.ManagedSetting;
import net.java21.crowfoot.api.managed.dto.IssueManagedDatabaseRequest;
import net.java21.crowfoot.api.managed.dto.ManagedCredentialResponse;
import net.java21.crowfoot.api.managed.dto.ManagedDatabaseListResponse;
import net.java21.crowfoot.api.managed.dto.ManagedDatabaseResponse;
import net.java21.crowfoot.api.managed.dto.ManagedIssueLimitResponse;
import net.java21.crowfoot.api.managed.dto.SetManagedIssueLimitRequest;
import net.java21.crowfoot.api.managed.provision.ManagedProvisioner;
import net.java21.crowfoot.api.managed.provision.ManagedProvisioners;
import net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository;
import net.java21.crowfoot.api.managed.repository.ManagedInstanceRepository;
import net.java21.crowfoot.api.managed.repository.ManagedSettingRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 매니지드 발급 단위 테스트 (08-core/07-managed-database.md Section 3.5~3.6) —
 * 발급(한도·스키마 규칙·커넥션 자동 등록·보상 회수)·철회(본인만·스키마 DROP·커넥션 삭제)·한도 요약.
 */
@ExtendWith(MockitoExtension.class)
class ManagedDatabaseServiceTest {

    @Mock
    private ManagedDatabaseRepository databaseRepository;
    @Mock
    private ManagedInstanceRepository instanceRepository;
    @Mock
    private ManagedSettingRepository settingRepository;
    @Mock
    private DbConnectionRepository connectionRepository;
    @Mock
    private net.java21.crowfoot.api.account.repository.UserRepository userRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private ManagedProvisioner provisioner;
    @Mock
    private ManagedProvisioner mysqlProvisioner;

    private ManagedDatabaseService databaseService;

    private static final String DEV_KEY = java.util.Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private ManagedInstance instance;

    @BeforeEach
    void setUp() {
        given(provisioner.dbmsType()).willReturn("postgresql");
        given(mysqlProvisioner.dbmsType()).willReturn("mysql");
        // PG default 매핑(database=인스턴스 것, 없으면 username 폴백·스키마=발급명) — MySQL은 별도 stub
        lenient().when(provisioner.connectionDatabaseName(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0) != null ? inv.getArgument(0) : inv.getArgument(1));
        lenient().when(provisioner.connectionSchemaName(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        databaseService = new ManagedDatabaseService(databaseRepository, instanceRepository,
                settingRepository, connectionRepository, userRepository, roleChecker, adminGuard,
                auditRecorder, new ConnectionCrypto(DEV_KEY),
                new ManagedProvisioners(List.of(provisioner, mysqlProvisioner)));
        instance = new ManagedInstance("Academy PG", "postgresql", "s3.java21.net", null, 8000,
                "crowfoot", "crowfoot", new ConnectionCrypto(DEV_KEY).encrypt("crowfoot123!"),
                true, 2L);
        ReflectionTestUtils.setField(instance, "id", 1L);
    }

    @Test
    @DisplayName("발급 — 워크스페이스 내 사용자 발급 수가 한도에 닿으면 MANAGED_LIMIT_EXCEEDED, 프로비저닝도 커넥션도 돌아가지 않는다")
    void issueRejectsAtLimit() {
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));
        // 지정 한도 도달 — 설정이 없으면 기본 5(인스턴스 설정과 무관)
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(5L);

        assertThatThrownBy(() -> databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_LIMIT_EXCEEDED);
        verify(provisioner, never()).provision(anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
        verify(connectionRepository, never()).save(any());
    }

    @Test
    @DisplayName("발급 — 관리자가 지정한 한도(기본 아님)를 기준으로 거부한다")
    void issueRejectsAtConfiguredLimit() {
        given(settingRepository.findById(ManagedSetting.KEY_ISSUE_LIMIT))
                .willReturn(Optional.of(new ManagedSetting(ManagedSetting.KEY_ISSUE_LIMIT, "3")));
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(3L);

        assertThatThrownBy(() -> databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_LIMIT_EXCEEDED);
        verify(provisioner, never()).provision(anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("한도 지정 — 관리자가 지정한 값(1~100)을 저장하고 감사를 남긴다")
    void updateIssueLimitPersistsValue() {
        given(settingRepository.findById(ManagedSetting.KEY_ISSUE_LIMIT)).willReturn(Optional.empty());

        ManagedIssueLimitResponse response =
                databaseService.updateIssueLimit(2L, new SetManagedIssueLimitRequest(8));

        ArgumentCaptor<ManagedSetting> settingCaptor = ArgumentCaptor.forClass(ManagedSetting.class);
        verify(settingRepository).save(settingCaptor.capture());
        assertThat(settingCaptor.getValue().getSettingKey()).isEqualTo("issue_limit");
        assertThat(settingCaptor.getValue().getSettingValue()).isEqualTo("8");
        assertThat(response.limit()).isEqualTo(8);
        verify(auditRecorder).record(eq(2L), eq("MANAGED_ISSUE_LIMIT_UPDATED"), eq("MANAGED_SETTING"),
                eq("issue_limit"), any());
    }

    @Test
    @DisplayName("한도 지정 — 범위(1~100) 밖은 INVALID_REQUEST로 거부한다")
    void updateIssueLimitValidatesRange() {
        assertThatThrownBy(() -> databaseService.updateIssueLimit(2L, new SetManagedIssueLimitRequest(0)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> databaseService.updateIssueLimit(2L, new SetManagedIssueLimitRequest(101)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(settingRepository, never()).save(any());
    }

    @Test
    @DisplayName("발급 — 비활성 인스턴스 지정은 INVALID_REQUEST로 거부한다")
    void issueRejectsInactiveInstance() {
        instance.setActive(false);
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));

        assertThatThrownBy(() -> databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(1L)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("발급 — 스키마+전용 계정을 만들고 커넥션·이력 모두 발급 계정 자격으로 저장한다(루트 미노출)")
    void issueCreatesSchemaAndConnection() {
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(0L);
        given(databaseRepository.findByInstanceIdAndSchemaName(1L, "cf_u2_d1")).willReturn(Optional.empty());
        given(connectionRepository.save(any())).willAnswer(inv -> {
            DbConnection stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 901L);
            return stored;
        });
        given(databaseRepository.save(any())).willAnswer(inv -> {
            ManagedDatabase stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 31L);
            ReflectionTestUtils.setField(stored, "createdAt", Instant.parse("2026-09-14T01:00:00Z"));
            return stored;
        });

        ManagedDatabaseResponse response = databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(1L));

        // 프로비저닝 — 인스턴스 자격(복호화본)으로 스키마·계정 생성. 비밀번호는 서버가 만든 값
        ArgumentCaptor<String> issuedPasswordCaptor = ArgumentCaptor.forClass(String.class);
        verify(provisioner).provision(eq("s3.java21.net"), eq(8000), eq("crowfoot"), eq("crowfoot"),
                eq("crowfoot123!"), eq("cf_u2_d1"), eq("cf_u2_d1"), issuedPasswordCaptor.capture());
        ArgumentCaptor<DbConnection> connectionCaptor = ArgumentCaptor.forClass(DbConnection.class);
        verify(connectionRepository).save(connectionCaptor.capture());
        DbConnection stored = connectionCaptor.getValue();
        assertThat(stored.getName()).isEqualTo("Academy PG #1");
        assertThat(stored.getHost()).isEqualTo("s3.java21.net");   // publicHost 미지정 → 접속 host 폴백
        assertThat(stored.getSchemaName()).isEqualTo("cf_u2_d1");
        assertThat(stored.getDatabaseName()).isEqualTo("crowfoot");
        // 커넥션 자격은 전부 발급 계정이다 — 인스턴스 루트(crowfoot)를 내주지 않는다
        assertThat(stored.getUsername()).isEqualTo("cf_u2_d1");
        assertThat(new ConnectionCrypto(DEV_KEY).decrypt(stored.getPassword()))
                .isEqualTo(issuedPasswordCaptor.getValue())
                .isNotEqualTo("crowfoot123!");
        // 이력도 발급 계정을 보관한다(접속 정보 조회 원천)
        ArgumentCaptor<ManagedDatabase> databaseCaptor = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(databaseRepository).save(databaseCaptor.capture());
        assertThat(databaseCaptor.getValue().getUsername()).isEqualTo("cf_u2_d1");
        assertThat(new ConnectionCrypto(DEV_KEY).decrypt(databaseCaptor.getValue().getPassword()))
                .isEqualTo(issuedPasswordCaptor.getValue());
        assertThat(response.schemaName()).isEqualTo("cf_u2_d1");
        assertThat(response.connectionId()).isEqualTo("901");
        verify(auditRecorder).record(eq(2L), eq("MANAGED_DATABASE_ISSUED"), eq("MANAGED_DATABASE"),
                eq("31"), any());
    }

    @Test
    @DisplayName("발급 — 커넥션 주소는 노출 주소(publicHost)를 쓰되 프로비저닝·보상은 내부 host로 수행한다")
    void issueUsesPublicHostForConnectionButInternalForProvisioning() {
        instance.setPublicHost("db.crowfoot.java21.net");
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(0L);
        given(databaseRepository.findByInstanceIdAndSchemaName(1L, "cf_u2_d1")).willReturn(Optional.empty());
        given(connectionRepository.save(any())).willAnswer(inv -> {
            DbConnection stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 905L);
            return stored;
        });
        given(databaseRepository.save(any())).willThrow(new IllegalStateException("db down"));

        // 저장 실패 → 보상 withdraw도 내부 host로 수행됨을 함께 검증한다
        assertThatThrownBy(() -> databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(1L)))
                .isInstanceOf(IllegalStateException.class);

        verify(provisioner).provision(eq("s3.java21.net"), eq(8000), eq("crowfoot"), eq("crowfoot"),
                eq("crowfoot123!"), eq("cf_u2_d1"), eq("cf_u2_d1"), anyString());
        verify(provisioner).withdraw(eq("s3.java21.net"), eq(8000), eq("crowfoot"), eq("crowfoot"),
                eq("crowfoot123!"), eq("cf_u2_d1"), eq("cf_u2_d1"));
        ArgumentCaptor<DbConnection> connectionCaptor = ArgumentCaptor.forClass(DbConnection.class);
        verify(connectionRepository).save(connectionCaptor.capture());
        assertThat(connectionCaptor.getValue().getHost()).isEqualTo("db.crowfoot.java21.net");
    }

    @Test
    @DisplayName("발급(MySQL) — 발급 이름이 곧 database라 커넥션 databaseName=발급 이름·schemaName=null로 매핑한다")
    void issueMySqlMapsConnectionToIssuedDatabase() {
        when(mysqlProvisioner.connectionDatabaseName(null, "root", "cf_u2_d1")).thenReturn("cf_u2_d1");
        when(mysqlProvisioner.connectionSchemaName("cf_u2_d1")).thenReturn(null);
        ManagedInstance mysql = new ManagedInstance("Academy MySQL", "mysql", "s4.java21.net", null, 13306,
                null, "root", new ConnectionCrypto(DEV_KEY).encrypt("Nhn123!@#"),
                true, 2L);
        ReflectionTestUtils.setField(mysql, "id", 2L);
        given(instanceRepository.findById(2L)).willReturn(Optional.of(mysql));
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(0L);
        given(databaseRepository.findByInstanceIdAndSchemaName(2L, "cf_u2_d1")).willReturn(Optional.empty());
        given(connectionRepository.save(any())).willAnswer(inv -> {
            DbConnection stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 903L);
            return stored;
        });
        given(databaseRepository.save(any())).willAnswer(inv -> {
            ManagedDatabase stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 33L);
            ReflectionTestUtils.setField(stored, "createdAt", Instant.parse("2026-09-14T01:00:00Z"));
            return stored;
        });

        databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(2L));

        // 인스턴스에 database가 없어도 프로비저닝은 접속만으로 수행한다(발급 시 database+계정 생성)
        verify(mysqlProvisioner).provision(eq("s4.java21.net"), eq(13306), isNull(), eq("root"),
                eq("Nhn123!@#"), eq("cf_u2_d1"), eq("cf_u2_d1"), anyString());
        ArgumentCaptor<DbConnection> connectionCaptor = ArgumentCaptor.forClass(DbConnection.class);
        verify(connectionRepository).save(connectionCaptor.capture());
        DbConnection stored = connectionCaptor.getValue();
        assertThat(stored.getDatabaseName()).isEqualTo("cf_u2_d1");
        assertThat(stored.getSchemaName()).isNull();
    }

    @Test
    @DisplayName("발급(PG database 생략) — 커넥션 databaseName은 username 폴백으로 확정해 채운다")
    void issuePostgresWithoutDatabaseFallsBackToUsername() {
        ManagedInstance noDatabase = new ManagedInstance("Academy PG", "postgresql", "s3.java21.net", null, 8000,
                null, "crowfoot", new ConnectionCrypto(DEV_KEY).encrypt("crowfoot123!"),
                true, 2L);
        ReflectionTestUtils.setField(noDatabase, "id", 3L);
        given(instanceRepository.findById(3L)).willReturn(Optional.of(noDatabase));
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(0L);
        given(databaseRepository.findByInstanceIdAndSchemaName(3L, "cf_u2_d1")).willReturn(Optional.empty());
        given(connectionRepository.save(any())).willAnswer(inv -> {
            DbConnection stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 904L);
            return stored;
        });
        given(databaseRepository.save(any())).willAnswer(inv -> {
            ManagedDatabase stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 34L);
            ReflectionTestUtils.setField(stored, "createdAt", Instant.parse("2026-09-14T01:00:00Z"));
            return stored;
        });

        databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(3L));

        // 인스턴스에 database가 없어도 발급 커넥션은 확정 database를 갖는다(NOT NULL 계약) — username 폴백
        ArgumentCaptor<DbConnection> connectionCaptor = ArgumentCaptor.forClass(DbConnection.class);
        verify(connectionRepository).save(connectionCaptor.capture());
        assertThat(connectionCaptor.getValue().getDatabaseName()).isEqualTo("crowfoot");
        assertThat(connectionCaptor.getValue().getSchemaName()).isEqualTo("cf_u2_d1");
    }

    @Test
    @DisplayName("발급 — 철회로 생긴 빈 번호는 건너뛴다(이름 충돌 검사로 seq 진행)")
    void issueSkipsTakenSequence() {
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(1L);
        given(databaseRepository.findByInstanceIdAndSchemaName(1L, "cf_u2_d2"))
                .willReturn(Optional.of(new ManagedDatabase(1L, 2L, 7L, "cf_u2_d2", 900L)));
        given(databaseRepository.findByInstanceIdAndSchemaName(1L, "cf_u2_d3")).willReturn(Optional.empty());
        given(connectionRepository.save(any())).willAnswer(inv -> {
            DbConnection stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 902L);
            return stored;
        });
        given(databaseRepository.save(any())).willAnswer(inv -> {
            ManagedDatabase stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 32L);
            ReflectionTestUtils.setField(stored, "createdAt", Instant.parse("2026-09-14T01:00:00Z"));
            return stored;
        });

        databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(1L));

        verify(provisioner).provision(anyString(), anyInt(), anyString(), anyString(), anyString(),
                eq("cf_u2_d3"), eq("cf_u2_d3"), anyString());
    }

    @Test
    @DisplayName("발급 — 로컬 저장 실패 시 생성한 스키마·계정을 회수(보상)한다")
    void issueCompensatesSchemaOnLocalFailure() {
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(0L);
        given(databaseRepository.findByInstanceIdAndSchemaName(1L, "cf_u2_d1")).willReturn(Optional.empty());
        given(connectionRepository.save(any())).willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(1L)))
                .isInstanceOf(IllegalStateException.class);

        verify(provisioner).withdraw("s3.java21.net", 8000, "crowfoot", "crowfoot",
                "crowfoot123!", "cf_u2_d1", "cf_u2_d1");
    }

    @Test
    @DisplayName("발급 — instanceId 생략 시 활성 첫 번째(등록순) 인스턴스로 발급한다")
    void issueDefaultsToFirstActive() {
        given(instanceRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc())
                .willReturn(List.of(instance));
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(0L);
        given(databaseRepository.findByInstanceIdAndSchemaName(1L, "cf_u2_d1")).willReturn(Optional.empty());
        given(connectionRepository.save(any())).willAnswer(inv -> {
            DbConnection stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 901L);
            return stored;
        });
        given(databaseRepository.save(any())).willAnswer(inv -> {
            ManagedDatabase stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 31L);
            ReflectionTestUtils.setField(stored, "createdAt", Instant.parse("2026-09-14T01:00:00Z"));
            return stored;
        });

        databaseService.issue(2L, 7L, new IssueManagedDatabaseRequest(null));

        verify(provisioner).provision(anyString(), anyInt(), anyString(), anyString(), anyString(),
                eq("cf_u2_d1"), eq("cf_u2_d1"), anyString());
    }

    @Test
    @DisplayName("철회 — 본인 발급이 아니면 존재 은닉(404)으로 감춘다")
    void revokeHidesOthersIssuance() {
        ManagedDatabase others = new ManagedDatabase(1L, 9L, 7L, "cf_u9_d1", 950L);
        ReflectionTestUtils.setField(others, "id", 31L);
        given(databaseRepository.findByIdAndWorkspaceId(31L, 7L)).willReturn(Optional.of(others));

        assertThatThrownBy(() -> databaseService.revoke(2L, 7L, 31L))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_DATABASE_NOT_FOUND);
        verify(provisioner, never()).withdraw(anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("철회 — 스키마·계정 DROP → 커넥션·이력 삭제 순서로 정리한다")
    void revokeDropsSchemaAccountAndConnection() {
        ManagedDatabase mine = new ManagedDatabase(1L, 2L, 7L, "cf_u2_d1", "cf_u2_d1",
                new ConnectionCrypto(DEV_KEY).encrypt("issued-pass"), 901L);
        ReflectionTestUtils.setField(mine, "id", 31L);
        given(databaseRepository.findByIdAndWorkspaceId(31L, 7L)).willReturn(Optional.of(mine));
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));

        databaseService.revoke(2L, 7L, 31L);

        verify(provisioner).withdraw("s3.java21.net", 8000, "crowfoot", "crowfoot",
                "crowfoot123!", "cf_u2_d1", "cf_u2_d1");
        verify(connectionRepository).deleteById(901L);
        verify(databaseRepository).delete(mine);
        verify(auditRecorder).record(eq(2L), eq("MANAGED_DATABASE_REVOKED"), eq("MANAGED_DATABASE"),
                eq("31"), any());
    }

    @Test
    @DisplayName("접속 정보 — 본인 발급의 접속 주소·발급 계정·비밀번호(복호화 평문)를 매핑과 함께 돌려준다")
    void credentialReturnsMappedConnectionInfoForOwner() {
        ManagedDatabase mine = new ManagedDatabase(1L, 2L, 7L, "cf_u2_d1", "cf_u2_d1",
                new ConnectionCrypto(DEV_KEY).encrypt("issued-pass-123!"), 901L);
        ReflectionTestUtils.setField(mine, "id", 31L);
        given(databaseRepository.findByIdAndWorkspaceId(31L, 7L)).willReturn(Optional.of(mine));
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));

        ManagedCredentialResponse response = databaseService.credential(2L, 7L, 31L);

        // PG 매핑 — database=인스턴스 것, schema=발급 이름. 계정·비밀번호는 발급 계정(루트 아님)
        assertThat(response.host()).isEqualTo("s3.java21.net");
        assertThat(response.port()).isEqualTo(8000);
        assertThat(response.databaseName()).isEqualTo("crowfoot");
        assertThat(response.schemaName()).isEqualTo("cf_u2_d1");
        assertThat(response.username()).isEqualTo("cf_u2_d1");
        assertThat(response.password()).isEqualTo("issued-pass-123!");
        assertThat(response.password()).isNotEqualTo("crowfoot123!");
        verify(auditRecorder).record(eq(2L), eq("MANAGED_DATABASE_CREDENTIAL_VIEWED"),
                eq("MANAGED_DATABASE"), eq("31"), any());
    }

    @Test
    @DisplayName("접속 정보 — 노출 주소(publicHost)가 있으면 접속 host 대신 그 값을 내준다(조회 시점 계산)")
    void credentialPrefersPublicHost() {
        instance.setPublicHost("db.crowfoot.java21.net");
        ManagedDatabase mine = new ManagedDatabase(1L, 2L, 7L, "cf_u2_d1", "cf_u2_d1",
                new ConnectionCrypto(DEV_KEY).encrypt("issued-pass-123!"), 901L);
        ReflectionTestUtils.setField(mine, "id", 31L);
        given(databaseRepository.findByIdAndWorkspaceId(31L, 7L)).willReturn(Optional.of(mine));
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));

        ManagedCredentialResponse response = databaseService.credential(2L, 7L, 31L);

        // 사용자에게는 노출 주소 — 이미 발급된 이력도 조회 시점에 계산되므로 즉시 반영된다
        assertThat(response.host()).isEqualTo("db.crowfoot.java21.net");
        assertThat(response.port()).isEqualTo(8000);
        assertThat(response.databaseName()).isEqualTo("crowfoot");
        assertThat(response.schemaName()).isEqualTo("cf_u2_d1");
    }

    @Test
    @DisplayName("접속 정보(MySQL) — 발급 이름이 곧 database라 databaseName=발급 이름·schemaName=null로 매핑한다")
    void credentialMySqlMapsIssuedDatabase() {
        when(mysqlProvisioner.connectionDatabaseName(null, "root", "cf_u2_d1")).thenReturn("cf_u2_d1");
        when(mysqlProvisioner.connectionSchemaName("cf_u2_d1")).thenReturn(null);
        ManagedInstance mysql = new ManagedInstance("Academy MySQL", "mysql", "s4.java21.net", null, 13306,
                null, "root", new ConnectionCrypto(DEV_KEY).encrypt("Nhn123!@#"), true, 2L);
        ReflectionTestUtils.setField(mysql, "id", 2L);
        ManagedDatabase mine = new ManagedDatabase(2L, 2L, 7L, "cf_u2_d1", "cf_u2_d1",
                new ConnectionCrypto(DEV_KEY).encrypt("issued-pass-123!"), 903L);
        ReflectionTestUtils.setField(mine, "id", 33L);
        given(databaseRepository.findByIdAndWorkspaceId(33L, 7L)).willReturn(Optional.of(mine));
        given(instanceRepository.findById(2L)).willReturn(Optional.of(mysql));

        ManagedCredentialResponse response = databaseService.credential(2L, 7L, 33L);

        assertThat(response.databaseName()).isEqualTo("cf_u2_d1");
        assertThat(response.schemaName()).isNull();
        assertThat(response.username()).isEqualTo("cf_u2_d1");
        assertThat(response.password()).isEqualTo("issued-pass-123!");
        assertThat(response.password()).isNotEqualTo("Nhn123!@#");
    }

    @Test
    @DisplayName("접속 정보 — 구방식(계정 없음) 이력은 루트 노출 방지 차원에서 거부한다")
    void credentialRejectsLegacyIssuanceWithoutAccount() {
        ManagedDatabase legacy = new ManagedDatabase(1L, 2L, 7L, "cf_u2_d1", 901L);
        ReflectionTestUtils.setField(legacy, "id", 31L);
        given(databaseRepository.findByIdAndWorkspaceId(31L, 7L)).willReturn(Optional.of(legacy));
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));

        assertThatThrownBy(() -> databaseService.credential(2L, 7L, 31L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_PROVISION_FAILED);
        verify(auditRecorder, never()).record(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("접속 정보 — 본인 발급이 아니면 존재 은닉(404)으로 감추고 감사도 남기지 않는다")
    void credentialHidesOthersIssuance() {
        ManagedDatabase others = new ManagedDatabase(1L, 9L, 7L, "cf_u9_d1", 950L);
        ReflectionTestUtils.setField(others, "id", 31L);
        given(databaseRepository.findByIdAndWorkspaceId(31L, 7L)).willReturn(Optional.of(others));

        assertThatThrownBy(() -> databaseService.credential(2L, 7L, 31L))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_DATABASE_NOT_FOUND);
        verify(auditRecorder, never()).record(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("목록 — 한도 요약은 워크스페이스 내 사용자 발급 수(모든 인스턴스 합산) 기준으로 잔여를 계산한다")
    void listSummarizesLimits() {
        ManagedDatabase mine = new ManagedDatabase(1L, 2L, 4L, "cf_u2_d1", 901L);
        ReflectionTestUtils.setField(mine, "id", 31L);
        ReflectionTestUtils.setField(mine, "createdAt", Instant.parse("2026-09-14T01:00:00Z"));
        given(databaseRepository.findByWorkspaceIdOrderByCreatedAtAscIdAsc(7L)).willReturn(List.of(mine));
        given(instanceRepository.findAllByOrderByCreatedAtAscIdAsc()).willReturn(List.of(instance));
        // 같은 워크스페이스의 다른 인스턴스에서 1건 더 — 인스턴스 구분 없이 합산 2건 사용
        given(databaseRepository.countByWorkspaceIdAndUserId(7L, 2L)).willReturn(2L);
        assertThat(ManagedDatabaseService.DEFAULT_ISSUE_LIMIT).isEqualTo(5);
        DbConnection connection = new DbConnection(7L, "Academy PG #1", "postgresql", "s3.java21.net",
                8000, "crowfoot", "cf_u2_d1", "crowfoot", new byte[0], 2L);
        ReflectionTestUtils.setField(connection, "id", 901L);
        given(connectionRepository.findById(901L)).willReturn(Optional.of(connection));

        ManagedDatabaseListResponse response = databaseService.list(2L, 7L);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.responses().get(0).schemaName()).isEqualTo("cf_u2_d1");
        assertThat(response.responses().get(0).connectionName()).isEqualTo("Academy PG #1");
        assertThat(response.limitSummary()).hasSize(1);
        assertThat(response.limitSummary().get(0).limit()).isEqualTo(5);
        assertThat(response.limitSummary().get(0).used()).isEqualTo(2);
        assertThat(response.limitSummary().get(0).remaining()).isEqualTo(3);
    }
}
