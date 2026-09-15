package net.java21.crowfoot.api.managed.service;

import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.dto.ConnectionTestResponse;
import net.java21.crowfoot.api.managed.domain.ManagedInstance;
import net.java21.crowfoot.api.managed.dto.CreateManagedInstanceRequest;
import net.java21.crowfoot.api.managed.dto.ManagedInstanceResponse;
import net.java21.crowfoot.api.managed.dto.UpdateManagedInstanceRequest;
import net.java21.crowfoot.api.managed.provision.ManagedProvisioner;
import net.java21.crowfoot.api.managed.provision.ManagedProvisioners;
import net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository;
import net.java21.crowfoot.api.managed.repository.ManagedInstanceRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 매니지드 인스턴스 관리 단위 테스트 (08-core/07-managed-database.md Section 3.2~3.4) —
 * 등록(자격 검증 수반·v1 postgresql 고정)·변경(자격 재검증)·삭제(발급 존재 시 거부).
 */
@ExtendWith(MockitoExtension.class)
class ManagedInstanceServiceTest {

    @Mock
    private ManagedInstanceRepository instanceRepository;
    @Mock
    private ManagedDatabaseRepository databaseRepository;
    @Mock
    private net.java21.crowfoot.api.account.repository.UserRepository userRepository;
    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private ManagedProvisioner provisioner;
    @Mock
    private ManagedProvisioner mysqlProvisioner;

    private ManagedInstanceService instanceService;

    private static final String DEV_KEY = java.util.Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @BeforeEach
    void setUp() {
        given(provisioner.dbmsType()).willReturn("postgresql");
        given(mysqlProvisioner.dbmsType()).willReturn("mysql");
        instanceService = new ManagedInstanceService(instanceRepository, databaseRepository,
                userRepository, adminGuard, auditRecorder, new ConnectionCrypto(DEV_KEY),
                new ManagedProvisioners(List.of(provisioner, mysqlProvisioner)));
    }

    private static ManagedInstance saved(long id) {
        ManagedInstance instance = new ManagedInstance("Academy PG", "postgresql", "s3.java21.net", null, 8000,
                "crowfoot", "crowfoot", new ConnectionCrypto(DEV_KEY).encrypt("crowfoot123!"),
                true, 2L);
        ReflectionTestUtils.setField(instance, "id", id);
        ReflectionTestUtils.setField(instance, "createdAt", Instant.parse("2026-09-14T01:00:00Z"));
        return instance;
    }

    @Test
    @DisplayName("등록 — 프로비저너 전략이 없는 DBMS는 INVALID_REQUEST(지원: postgresql·mysql)")
    void createRejectsUnsupportedDbms() {
        assertThatThrownBy(() -> instanceService.create(2L, new CreateManagedInstanceRequest(
                "Oracle 인스턴스", "oracle", "db.dev", null, 1521, "orcl", "app", "pw", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(instanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("등록(MySQL) — database 없이 등록한다(발급 시 database 생성) — verify·저장 모두 null")
    void createMySqlWithoutDatabase() {
        given(instanceRepository.save(any())).willAnswer(inv -> {
            ManagedInstance stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 10L);
            return stored;
        });

        ManagedInstanceResponse response = instanceService.create(2L, new CreateManagedInstanceRequest(
                "Academy MySQL", "mysql", "s4.java21.net", null, 13306, null,
                "root", "Nhn123!@#", null));

        verify(mysqlProvisioner).verify("s4.java21.net", 13306, null, "root", "Nhn123!@#");
        ArgumentCaptor<ManagedInstance> captor = ArgumentCaptor.forClass(ManagedInstance.class);
        verify(instanceRepository).save(captor.capture());
        assertThat(captor.getValue().getDatabaseName()).isNull();
        assertThat(response.databaseName()).isNull();
    }

    @Test
    @DisplayName("등록(PostgreSQL) — database도 생략 가능하다(빈 칸→null 정규화, JDBC username 폴백)")
    void createPostgresWithoutDatabase() {
        given(instanceRepository.save(any())).willAnswer(inv -> {
            ManagedInstance stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 11L);
            return stored;
        });

        instanceService.create(2L, new CreateManagedInstanceRequest(
                "Academy PG", "postgresql", "s3.java21.net", null, 8000, "  ",
                "crowfoot", "crowfoot123!", null));

        // 빈 칸은 null로 정규화 — pgjdbc 폴백(username database)으로 접속 검증
        verify(provisioner).verify("s3.java21.net", 8000, null, "crowfoot", "crowfoot123!");
        ArgumentCaptor<ManagedInstance> captor = ArgumentCaptor.forClass(ManagedInstance.class);
        verify(instanceRepository).save(captor.capture());
        assertThat(captor.getValue().getDatabaseName()).isNull();
    }

    @Test
    @DisplayName("등록 — publicHost는 trim 정규화해 저장하고 응답에 내려간다(빈 칸→null = host 노출)")
    void createNormalizesPublicHost() {
        given(instanceRepository.save(any())).willAnswer(inv -> {
            ManagedInstance stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 12L);
            return stored;
        });

        ManagedInstanceResponse response = instanceService.create(2L, new CreateManagedInstanceRequest(
                "Academy PG", "postgresql", "s3.java21.net", " db.crowfoot.java21.net ", 8000, "crowfoot",
                "crowfoot", "crowfoot123!", null));

        ArgumentCaptor<ManagedInstance> captor = ArgumentCaptor.forClass(ManagedInstance.class);
        verify(instanceRepository).save(captor.capture());
        assertThat(captor.getValue().getPublicHost()).isEqualTo("db.crowfoot.java21.net");
        assertThat(response.publicHost()).isEqualTo("db.crowfoot.java21.net");

        // 빈 칸은 null로 정규화 — 접속 host를 그대로 노출(폴백)
        instanceService.create(2L, new CreateManagedInstanceRequest(
                "Academy PG", "postgresql", "s3.java21.net", "  ", 8000, "crowfoot",
                "crowfoot", "crowfoot123!", null));
        ArgumentCaptor<ManagedInstance> blankCaptor = ArgumentCaptor.forClass(ManagedInstance.class);
        verify(instanceRepository, times(2)).save(blankCaptor.capture());
        assertThat(blankCaptor.getValue().getPublicHost()).isNull();
    }

    @Test
    @DisplayName("등록 — 자격 검증(SELECT 1)을 통과하면 비밀번호는 암호문으로 저장된다")
    void createVerifiesAndEncrypts() {
        given(instanceRepository.save(any())).willAnswer(inv -> {
            ManagedInstance stored = inv.getArgument(0);
            ReflectionTestUtils.setField(stored, "id", 9L);
            return stored;
        });

        ManagedInstanceResponse response = instanceService.create(2L, new CreateManagedInstanceRequest(
                "Academy PG", "postgresql", "s3.java21.net", null, 8000, "crowfoot",
                "crowfoot", "crowfoot123!", null));

        verify(provisioner).verify("s3.java21.net", 8000, "crowfoot", "crowfoot", "crowfoot123!");
        ArgumentCaptor<ManagedInstance> captor = ArgumentCaptor.forClass(ManagedInstance.class);
        verify(instanceRepository).save(captor.capture());
        ManagedInstance stored = captor.getValue();
        assertThat(new ConnectionCrypto(DEV_KEY).decrypt(stored.getPassword())).isEqualTo("crowfoot123!");
        assertThat(stored.isActive()).isTrue();               // 생략 시 활성
        assertThat(response.instanceId()).isEqualTo("9");
        verify(auditRecorder).record(eq(2L), eq("MANAGED_INSTANCE_CREATED"), eq("MANAGED_INSTANCE"),
                eq("9"), any());
    }

    @Test
    @DisplayName("등록 — 자격 검증 실패(MANAGED_INSTANCE_UNREACHABLE)면 저장하지 않는다")
    void createRejectsUnreachableCredential() {
        willThrow(new BusinessException(ErrorCode.MANAGED_INSTANCE_UNREACHABLE))
                .given(provisioner).verify(anyString(), anyInt(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> instanceService.create(2L, new CreateManagedInstanceRequest(
                "깨진 자격", "postgresql", "s3.java21.net", null, 8000, "crowfoot",
                "crowfoot", "wrong", null)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_INSTANCE_UNREACHABLE);
        verify(instanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("변경 — 자격이 하나라도 오면 새 조합으로 재검증한다(password 미전송 시 기존 비밀번호 조합)")
    void updateReverifiesCredential() {
        ManagedInstance instance = saved(1L);
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));

        instanceService.update(2L, 1L, new UpdateManagedInstanceRequest(
                null, "s3.java21.net", null, 5432, null, null, null, null));

        // host·port가 바뀌었으니 기존 password(crowfoot123!)로 된 새 조합 검증
        verify(provisioner).verify("s3.java21.net", 5432, "crowfoot", "crowfoot", "crowfoot123!");
    }

    @Test
    @DisplayName("변경 — 표시명·한도·활성만 바꿀 때는 자격 검증을 돌리지 않는다")
    void updateWithoutCredentialSkipsVerify() {
        ManagedInstance instance = saved(1L);
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));

        instanceService.update(2L, 1L, new UpdateManagedInstanceRequest(
                "새 이름", null, null, null, null, null, null, false));

        verify(provisioner, never()).verify(anyString(), anyInt(), anyString(), anyString(), anyString());
        assertThat(instance.getDisplayName()).isEqualTo("새 이름");
        assertThat(instance.isActive()).isFalse();
    }

    @Test
    @DisplayName("변경 — publicHost만 바꿀 때는 자격 재검증을 돌리지 않는다(표기 전용)")
    void updatePublicHostSkipsReverify() {
        ManagedInstance instance = saved(1L);
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));

        ManagedInstanceResponse response = instanceService.update(2L, 1L, new UpdateManagedInstanceRequest(
                null, null, "db.crowfoot.java21.net", null, null, null, null, null));

        verify(provisioner, never()).verify(anyString(), anyInt(), anyString(), anyString(), anyString());
        assertThat(instance.getPublicHost()).isEqualTo("db.crowfoot.java21.net");
        assertThat(response.publicHost()).isEqualTo("db.crowfoot.java21.net");
        // 접속 host는 그대로 — 노출 주소 변경이 자격에 스며들지 않는다
        assertThat(instance.getHost()).isEqualTo("s3.java21.net");
    }

    @Test
    @DisplayName("변경 — publicHost 빈 칸 전송은 노출 주소를 제거하고(host 폴백) null 전송은 유지한다")
    void updatePublicHostBlankRemovesNullKeeps() {
        ManagedInstance instance = saved(1L);
        instance.setPublicHost("db.crowfoot.java21.net");
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));

        // null = 변경 없음
        instanceService.update(2L, 1L, new UpdateManagedInstanceRequest(
                null, null, null, null, null, null, null, null));
        assertThat(instance.getPublicHost()).isEqualTo("db.crowfoot.java21.net");

        // 빈 칸 = 제거(host 폴백)
        instanceService.update(2L, 1L, new UpdateManagedInstanceRequest(
                null, null, "  ", null, null, null, null, null));
        assertThat(instance.getPublicHost()).isNull();
        verify(provisioner, never()).verify(anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("접속 테스트 — 저장된 자격(복호화본)으로 SELECT 1 결과를 그대로 돌려준다")
    void testReportsConnectivity() {
        given(instanceRepository.findById(1L)).willReturn(Optional.of(saved(1L)));
        when(provisioner.test("s3.java21.net", 8000, "crowfoot", "crowfoot", "crowfoot123!"))
                .thenReturn(ConnectionTestResponse.ok(42L));

        ConnectionTestResponse response = instanceService.test(2L, 1L);

        assertThat(response.connected()).isTrue();
        assertThat(response.latencyMs()).isEqualTo(42L);
    }

    @Test
    @DisplayName("접속 테스트 — 접속 실패는 예외가 아니라 계약 응답(connected=false + 문구)으로 흘러간다")
    void testReturnsFailureAsContract() {
        given(instanceRepository.findById(1L)).willReturn(Optional.of(saved(1L)));
        when(provisioner.test("s3.java21.net", 8000, "crowfoot", "crowfoot", "crowfoot123!"))
                .thenReturn(ConnectionTestResponse.fail("접속이 거부되었습니다"));

        ConnectionTestResponse response = instanceService.test(2L, 1L);

        assertThat(response.connected()).isFalse();
        assertThat(response.message()).isEqualTo("접속이 거부되었습니다");
    }

    @Test
    @DisplayName("삭제 — 발급이 남아 있으면 MANAGED_INSTANCE_IN_USE로 거부한다")
    void deleteBlockedWhileInUse() {
        ManagedInstance instance = saved(1L);
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));
        given(databaseRepository.countByInstanceId(1L)).willReturn(3L);

        assertThatThrownBy(() -> instanceService.delete(2L, 1L))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_INSTANCE_IN_USE);
        verify(instanceRepository, never()).delete(any());
    }

    @Test
    @DisplayName("삭제 — 발급이 없으면 삭제하고 감사를 남긴다")
    void deleteWhenEmpty() {
        ManagedInstance instance = saved(1L);
        given(instanceRepository.findById(1L)).willReturn(Optional.of(instance));
        given(databaseRepository.countByInstanceId(1L)).willReturn(0L);

        instanceService.delete(2L, 1L);

        verify(instanceRepository).delete(instance);
        verify(auditRecorder).record(eq(2L), eq("MANAGED_INSTANCE_DELETED"), eq("MANAGED_INSTANCE"),
                eq("1"), any());
    }
}
