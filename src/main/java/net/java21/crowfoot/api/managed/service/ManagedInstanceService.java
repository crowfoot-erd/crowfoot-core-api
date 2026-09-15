package net.java21.crowfoot.api.managed.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
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
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 매니지드 루트 인스턴스 관리 API (08-core/07-managed-database.md Section 3.2~3.4) —
 * 목록·등록(접속 검증 수반)·변경·삭제(발급 존재 시 거부).
 *
 * <p>등록·자격 변경은 곧 검증이다 — 제출된 자격으로 SELECT 1을 실행해 실패하면
 * 저장하지 않는다(잘못된 root 자격이 발급 경로에서 처음 발견되는 일을 막는다).
 * password는 평문 요청 → 즉시 AES-256-GCM 암호화, 응답에는 어떤 형태로도 내보내지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ManagedInstanceService {

    private final ManagedInstanceRepository instanceRepository;
    private final ManagedDatabaseRepository databaseRepository;
    private final UserRepository userRepository;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;
    private final ConnectionCrypto crypto;
    private final ManagedProvisioners provisioners;

    /** 목록 — 등록순, 발급 수(issuedCount) 포함 */
    @Transactional(readOnly = true)
    public ListApiResponse<ManagedInstanceResponse> list(long adminId) {
        adminGuard.requireAdmin(adminId);
        List<ManagedInstanceResponse> responses = instanceRepository.findAllByOrderByCreatedAtAscIdAsc()
                .stream()
                .map(this::toResponse)
                .toList();
        auditRecorder.record(adminId, "ADMIN_MANAGED_INSTANCES_LISTED", "MANAGED_INSTANCE", "ALL", null);
        return ListApiResponse.of(responses);
    }

    /** 등록 — 자격으로 SELECT 1 검증 후 저장(실패 시 MANAGED_INSTANCE_UNREACHABLE) */
    @Transactional
    public ManagedInstanceResponse create(long adminId, CreateManagedInstanceRequest request) {
        adminGuard.requireAdmin(adminId);
        String dbmsType = request.dbmsType().trim();
        ManagedProvisioner provisioner = requireProvisioner(dbmsType);
        String databaseName = resolveDatabaseName(request.databaseName());
        String publicHost = resolvePublicHost(request.publicHost());
        provisioner.verify(request.host().trim(), request.port(), databaseName,
                request.username().trim(), request.password());

        ManagedInstance saved = instanceRepository.save(new ManagedInstance(
                request.displayName().trim(), dbmsType, request.host().trim(), publicHost, request.port(),
                databaseName, request.username().trim(),
                crypto.encrypt(request.password()),
                request.isActive() == null || request.isActive(),
                adminId));
        auditRecorder.record(adminId, "MANAGED_INSTANCE_CREATED", "MANAGED_INSTANCE",
                Long.toString(saved.getId()), Map.of(
                        "displayName", saved.getDisplayName(),
                        "host", saved.getHost(),
                        "databaseName", saved.getDatabaseName() == null ? "" : saved.getDatabaseName()));
        return toResponse(saved);
    }

    /** 변경 — PATCH 의미론(null은 변경 없음). 자격(host·port·database·username·password)이 하나라도 오면 새 조합으로 재검증한다.
     *  publicHost는 표기 전용이라 재검증을 트리거하지 않는다(빈 칸 전송 = 노출 주소 제거) */
    @Transactional
    public ManagedInstanceResponse update(long adminId, long instanceId, UpdateManagedInstanceRequest request) {
        adminGuard.requireAdmin(adminId);
        ManagedInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGED_INSTANCE_NOT_FOUND));
        ManagedProvisioner provisioner = requireProvisioner(instance.getDbmsType());

        boolean credentialTouched = request.host() != null || request.port() != null
                || request.databaseName() != null || request.username() != null || request.password() != null;
        String host = request.host() != null ? request.host().trim() : instance.getHost();
        int port = request.port() != null ? request.port() : instance.getPort();
        // databaseName은 오면 정규화(빈 칸→null = 제거), 안 오면 유지 — PATCH 의미론
        String databaseName = request.databaseName() != null
                ? resolveDatabaseName(request.databaseName())
                : instance.getDatabaseName();
        // publicHost도 같은 정규화를 따르되 재검증(credentialTouched) 대상이 아니다 — 표기 전용
        String publicHost = request.publicHost() != null
                ? resolvePublicHost(request.publicHost())
                : instance.getPublicHost();
        String username = request.username() != null ? request.username().trim() : instance.getUsername();
        if (credentialTouched) {
            String password = request.password() != null ? request.password()
                    : crypto.decrypt(instance.getPassword());
            provisioner.verify(host, port, databaseName, username, password);
        }

        if (request.displayName() != null) {
            instance.setDisplayName(request.displayName().trim());
        }
        instance.setHost(host);
        instance.setPublicHost(publicHost);
        instance.setPort(port);
        instance.setDatabaseName(databaseName);
        instance.setUsername(username);
        if (request.password() != null) {
            instance.setPassword(crypto.encrypt(request.password()));
        }
        if (request.isActive() != null) {
            instance.setActive(request.isActive());
        }

        auditRecorder.record(adminId, "MANAGED_INSTANCE_UPDATED", "MANAGED_INSTANCE",
                Long.toString(instance.getId()), null);
        return toResponse(instance);
    }

    /** 삭제 — 발급이 하나라도 남아 있으면 거부(루트 자격을 잃은 스키마는 회수 불가) */
    @Transactional
    public void delete(long adminId, long instanceId) {
        adminGuard.requireAdmin(adminId);
        ManagedInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGED_INSTANCE_NOT_FOUND));
        if (databaseRepository.countByInstanceId(instanceId) > 0) {
            throw new BusinessException(ErrorCode.MANAGED_INSTANCE_IN_USE);
        }
        instanceRepository.delete(instance);
        auditRecorder.record(adminId, "MANAGED_INSTANCE_DELETED", "MANAGED_INSTANCE",
                Long.toString(instanceId), Map.of("displayName", instance.getDisplayName()));
    }

    /** 접속 테스트 — 저장된 자격으로 SELECT 1. 실패도 예외가 아닌 계약 응답(200 + connected:false)이라
     *  트랜잭션을 물지 않는다(커넥션 테스트 06 Section 3.5와 같은 계약) */
    @Transactional(readOnly = true)
    public ConnectionTestResponse test(long adminId, long instanceId) {
        adminGuard.requireAdmin(adminId);
        ManagedInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGED_INSTANCE_NOT_FOUND));
        return requireProvisioner(instance.getDbmsType()).test(
                instance.getHost(), instance.getPort(), instance.getDatabaseName(),
                instance.getUsername(), crypto.decrypt(instance.getPassword()));
    }

    /** 프로비저닝 전략이 등록된 DBMS만 등록 받는다 — 신규 DBMS는 ManagedProvisioner 구현 추가 */
    private ManagedProvisioner requireProvisioner(String dbmsType) {
        ManagedProvisioner provisioner = provisioners.forDbmsType(dbmsType);
        if (provisioner == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "매니지드 발급을 지원하지 않는 DBMS입니다");
        }
        return provisioner;
    }

    /** databaseName 정규화 — 두 DBMS 모두 생략 가능(PostgreSQL은 username database 폴백,
     *  MySQL은 발급 시 database 생성). 빈 칸은 null로 정규화한다 */
    private static String resolveDatabaseName(String requested) {
        String trimmed = requested == null ? null : requested.trim();
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    /** publicHost 정규화 — 표기 전용 노출 주소. 빈 칸은 null(= host 폴백)로 정규화한다 */
    private static String resolvePublicHost(String requested) {
        String trimmed = requested == null ? null : requested.trim();
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private ManagedInstanceResponse toResponse(ManagedInstance instance) {
        User creator = userRepository.findById(instance.getCreatedBy()).orElse(null);
        UserRefResponse createdBy = creator == null
                ? null
                : new UserRefResponse(Long.toString(creator.getId()), creator.getName());
        return new ManagedInstanceResponse(
                Long.toString(instance.getId()),
                instance.getDisplayName(),
                instance.getDbmsType(),
                instance.getHost(),
                instance.getPublicHost(),
                instance.getPort(),
                instance.getDatabaseName(),
                instance.getUsername(),
                instance.isActive(),
                databaseRepository.countByInstanceId(instance.getId()),
                createdBy,
                instance.getCreatedAt());
    }
}
