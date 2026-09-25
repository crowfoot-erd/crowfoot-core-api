package net.java21.crowfoot.api.managed.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
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
import net.java21.crowfoot.api.managed.dto.ManagedLimitSummary;
import net.java21.crowfoot.api.managed.dto.SetManagedIssueLimitRequest;
import net.java21.crowfoot.api.managed.provision.IssuedPasswords;
import net.java21.crowfoot.api.managed.provision.ManagedProvisioner;
import net.java21.crowfoot.api.managed.provision.ManagedProvisioners;
import net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository;
import net.java21.crowfoot.api.managed.repository.ManagedInstanceRepository;
import net.java21.crowfoot.api.managed.repository.ManagedSettingRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 매니지드 발급 API (08-core/07-managed-database.md Section 3.5~3.6) —
 * 발급 목록·한도 요약·발급(스키마 생성 + 커넥션 자동 등록)·철회(스키마 DROP + 커넥션 삭제).
 *
 * <p>발급 순서: 한도 검사 → 스키마+전용 계정 프로비저닝(외부) → 커넥션·이력 저장(로컬).
 * 로컬 저장이 실패하면 만든 스키마·계정을 회수(보상)한다. 철회는 반대로 외부 DROP →
 * 로컬 삭제 순서로, 로컬 실패 시 외부는 이미 없으므로 재철회로 수습된다(DROP IF EXISTS).
 *
 * <p>발급마다 스키마 전용 DB 계정(계정명 = 스키마명, 비밀번호는 서버 생성·AES-256-GCM
 * 암호문 보관)을 만들고 커넥션·접속 정보가 모두 그 자격을 쓴다 — 인스턴스 루트 자격은
 * 프로비저닝에만 쓰이고 사용자에게 노출되지 않는다. database·스키마 매핑은 프로비저너
 * 전략이 정한다(PostgreSQL: schemaName=발급 스키마 #130 계약 / MySQL: databaseName=발급 이름).
 * 이름 외 수정·삭제는 ConnectionService가 차단한다(06 Section 3.3~3.4 보호).
 */
@Service
@RequiredArgsConstructor
public class ManagedDatabaseService {

    /** 워크스페이스 내 사용자당 발급 한도 기본값 — 관리자가 managed_settings(issue_limit)로 지정한다 */
    public static final int DEFAULT_ISSUE_LIMIT = 5;

    private final ManagedDatabaseRepository databaseRepository;
    private final ManagedInstanceRepository instanceRepository;
    private final ManagedSettingRepository settingRepository;
    private final DbConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;
    private final ConnectionCrypto crypto;
    private final ManagedProvisioners provisioners;

    /** 지정된 발급 한도(관리자) — 워크스페이스 내 사용자당(모든 인스턴스 합산). 설정이 없으면 기본값 */
    @Transactional(readOnly = true)
    public int issueLimit() {
        return settingRepository.findById(ManagedSetting.KEY_ISSUE_LIMIT)
                .map(setting -> parseLimit(setting.getSettingValue()))
                .orElse(DEFAULT_ISSUE_LIMIT);
    }

    /** 발급 한도 지정(관리자) — 1~100. 즉시 다음 발급·한도 요약에 반영된다 */
    @Transactional
    public ManagedIssueLimitResponse updateIssueLimit(long adminId, SetManagedIssueLimitRequest request) {
        adminGuard.requireAdmin(adminId);
        if (request == null || request.limit() == null || request.limit() < 1 || request.limit() > 100) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.managed.limit-range");
        }
        int limit = request.limit();
        ManagedSetting setting = settingRepository.findById(ManagedSetting.KEY_ISSUE_LIMIT)
                .orElseGet(() -> new ManagedSetting(ManagedSetting.KEY_ISSUE_LIMIT, null));
        setting.setSettingValue(Integer.toString(limit));
        settingRepository.save(setting);
        auditRecorder.record(adminId, "MANAGED_ISSUE_LIMIT_UPDATED", "MANAGED_SETTING",
                ManagedSetting.KEY_ISSUE_LIMIT, Map.of("limit", limit));
        return new ManagedIssueLimitResponse(limit);
    }

    /** 저장값 해석 — 못 읽는 값이면 기본값으로 돌아간다(설정 행이 관리 경로에서만 쓰이므로 실질적으로 불일치 없음) */
    private static int parseLimit(String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return DEFAULT_ISSUE_LIMIT;
        }
    }

    /** 발급 목록(멤버 전체) + 요청자 기준 인스턴스별 한도 요약 */
    @Transactional(readOnly = true)
    public ManagedDatabaseListResponse list(long userId, long workspaceId) {
        roleChecker.requireMember(userId, workspaceId);
        List<ManagedDatabaseResponse> responses = databaseRepository
                .findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)
                .stream()
                .map(this::toResponse)
                .toList();
        List<ManagedLimitSummary> limitSummary = new ArrayList<>();
        // 사용량은 워크스페이스 내 이 사용자의 발급(모든 인스턴스 합산) 하나 —
        // 인스턴스 행마다 같은 값이 잔여 계산에 쓰인다
        long used = databaseRepository.countByWorkspaceIdAndUserId(workspaceId, userId);
        int limit = issueLimit();
        for (ManagedInstance instance : instanceRepository.findAllByOrderByCreatedAtAscIdAsc()) {
            limitSummary.add(new ManagedLimitSummary(
                    Long.toString(instance.getId()),
                    instance.getDisplayName(),
                    instance.isActive(),
                    limit,
                    used,
                    Math.max(0, limit - used)));
        }
        return ManagedDatabaseListResponse.of(responses, limitSummary);
    }

    /** 발급(Editor 이상) — 한도 검사 → 스키마+전용 계정 프로비저닝 → 커넥션 자동 등록 → 이력 저장.
     *  인스턴스 루트 자격은 프로비저닝에만 쓰고 발급 계정(스키마 한정 권한)을 사용자에게 내준다 */
    @Transactional
    public ManagedDatabaseResponse issue(long userId, long workspaceId, IssueManagedDatabaseRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        ManagedInstance instance = resolveInstance(request);
        if (!instance.isActive()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.managed.inactive");
        }
        ManagedProvisioner provisioner = provisioners.forDbmsType(instance.getDbmsType());
        if (provisioner == null) {
            throw new BusinessException(ErrorCode.MANAGED_PROVISION_FAILED,
                    "이 인스턴스의 DBMS는 프로비저닝을 지원하지 않습니다");
        }

        // 한도는 인스턴스와 무관한 워크스페이스 내 사용자 발급 수(모든 인스턴스 합산) 기준 —
        // 값은 관리자가 지정(managed_settings.issue_limit, 없으면 기본 5)
        long used = databaseRepository.countByWorkspaceIdAndUserId(workspaceId, userId);
        int limit = issueLimit();
        if (used >= limit) {
            throw new BusinessException(ErrorCode.MANAGED_LIMIT_EXCEEDED,
                    "워크스페이스 발급 한도(" + limit + "개)에 도달했습니다 — 철회 후 다시 시도하세요");
        }
        String schemaName = nextSchemaName(instance.getId(), userId, used);
        // 전용 계정 — 계정명 = 스키마명, 비밀번호는 서버가 생성(사용자 입력이 조립에 오지 않는다)
        String issuedUsername = schemaName;
        String issuedPassword = IssuedPasswords.generate();
        byte[] issuedSecret = crypto.encrypt(issuedPassword);

        provisioner.provision(instance.getHost(), instance.getPort(), instance.getDatabaseName(),
                instance.getUsername(), crypto.decrypt(instance.getPassword()),
                schemaName, issuedUsername, issuedPassword);

        // 외부 스키마·계정은 트랜잭션 롤백으로 돌아오지 않는다 — 로컬 저장 실패 시 회수(보상)
        try {
            // 커넥션 매핑은 전략이 정한다 — PG: database=인스턴스 것·schemaName=발급 스키마(#130 계약),
            // MySQL: database=스키마가 곧 database라 databaseName=발급 이름·schemaName=null.
            // 자격은 전부 발급 계정이다(인스턴스 루트는 내주지 않는다).
            // 커넥션 주소는 사용자 노출 주소(publicHost 폴백) — 프로비저닝은 내부 host로 수행했다
            DbConnection connection = connectionRepository.save(new DbConnection(
                    workspaceId,
                    instance.getDisplayName() + " #" + schemaSuffix(schemaName),
                    instance.getDbmsType(),
                    displayHost(instance),
                    instance.getPort(),
                    provisioner.connectionDatabaseName(instance.getDatabaseName(), instance.getUsername(),
                            schemaName),
                    provisioner.connectionSchemaName(schemaName),
                    issuedUsername,
                    issuedSecret,
                    userId));
            ManagedDatabase saved = databaseRepository.save(new ManagedDatabase(
                    instance.getId(), userId, workspaceId, schemaName, issuedUsername, issuedSecret,
                    connection.getId()));
            auditRecorder.record(userId, "MANAGED_DATABASE_ISSUED", "MANAGED_DATABASE",
                    Long.toString(saved.getId()), Map.of(
                            "schemaName", schemaName,
                            "instanceId", Long.toString(instance.getId()),
                            "connectionId", Long.toString(connection.getId())));
            return toResponse(saved);
        } catch (RuntimeException e) {
            provisioner.withdraw(instance.getHost(), instance.getPort(), instance.getDatabaseName(),
                    instance.getUsername(), crypto.decrypt(instance.getPassword()),
                    schemaName, issuedUsername);
            throw e;
        }
    }

    /** 철회(Editor 이상·본인만) — 스키마·계정 DROP → 발급 커넥션·이력 삭제.
     *  구방식 이력(username 없음)은 스키마만 회수한다 */
    @Transactional
    public void revoke(long userId, long workspaceId, long databaseId) {
        roleChecker.requireEditor(userId, workspaceId);
        ManagedDatabase managed = databaseRepository.findByIdAndWorkspaceId(databaseId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGED_DATABASE_NOT_FOUND));
        // 타인 발급은 존재 은닉 — 404로 동일하게 감춘다
        if (!managed.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.MANAGED_DATABASE_NOT_FOUND);
        }
        ManagedInstance instance = instanceRepository.findById(managed.getInstanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGED_INSTANCE_NOT_FOUND));
        ManagedProvisioner provisioner = provisioners.forDbmsType(instance.getDbmsType());
        if (provisioner == null) {
            throw new BusinessException(ErrorCode.MANAGED_PROVISION_FAILED,
                    "이 인스턴스의 DBMS는 프로비저닝을 지원하지 않습니다");
        }

        provisioner.withdraw(instance.getHost(), instance.getPort(), instance.getDatabaseName(),
                instance.getUsername(), crypto.decrypt(instance.getPassword()),
                managed.getSchemaName(), managed.getUsername());

        connectionRepository.deleteById(managed.getConnectionId());
        databaseRepository.delete(managed);
        auditRecorder.record(userId, "MANAGED_DATABASE_REVOKED", "MANAGED_DATABASE",
                Long.toString(managed.getId()), Map.of(
                        "schemaName", managed.getSchemaName(),
                        "instanceId", Long.toString(instance.getId()),
                        "connectionId", Long.toString(managed.getConnectionId())));
    }

    /** 접속 정보 조회(본인 발급만) — 외부 클라이언트 접속용 유일한 비밀번호 노출 경로.
     *  내주는 자격은 발급 계정(스키마 한정)이며 인스턴스 루트는 절대 노출되지 않는다.
     *  database·스키마 매핑은 발급 커넥션과 같은 규칙을 프로비저너에서 다시 계산하고,
     *  조회마다 감사를 남긴다 */
    @Transactional(readOnly = true)
    public ManagedCredentialResponse credential(long userId, long workspaceId, long databaseId) {
        roleChecker.requireMember(userId, workspaceId);
        ManagedDatabase managed = databaseRepository.findByIdAndWorkspaceId(databaseId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGED_DATABASE_NOT_FOUND));
        // 타인 발급은 존재 은닉 — 404로 동일하게 감춘다(철회와 같은 처리)
        if (!managed.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.MANAGED_DATABASE_NOT_FOUND);
        }
        ManagedInstance instance = instanceRepository.findById(managed.getInstanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGED_INSTANCE_NOT_FOUND));
        ManagedProvisioner provisioner = provisioners.forDbmsType(instance.getDbmsType());
        if (provisioner == null) {
            throw new BusinessException(ErrorCode.MANAGED_PROVISION_FAILED,
                    "이 인스턴스의 DBMS는 프로비저닝을 지원하지 않습니다");
        }
        // 구방식(스키마-only) 이력에는 전용 계정이 없다 — 루트 자격을 내주지 않도록 거부한다
        if (managed.getUsername() == null || managed.getPassword() == null) {
            throw new BusinessException(ErrorCode.MANAGED_PROVISION_FAILED,
                    "이전 방식 발급이라 전용 계정 정보가 없습니다 — 철회 후 다시 발급받으세요");
        }

        auditRecorder.record(userId, "MANAGED_DATABASE_CREDENTIAL_VIEWED", "MANAGED_DATABASE",
                Long.toString(managed.getId()), Map.of(
                        "schemaName", managed.getSchemaName(),
                        "instanceId", Long.toString(instance.getId())));
        return new ManagedCredentialResponse(
                Long.toString(managed.getId()),
                instance.getDisplayName(),
                instance.getDbmsType(),
                displayHost(instance),
                instance.getPort(),
                provisioner.connectionDatabaseName(instance.getDatabaseName(), instance.getUsername(),
                        managed.getSchemaName()),
                provisioner.connectionSchemaName(managed.getSchemaName()),
                managed.getUsername(),
                crypto.decrypt(managed.getPassword()));
    }

    /** 발급 인스턴스 결정 — 지정이 없으면 활성 첫 번째(등록순) */
    private ManagedInstance resolveInstance(IssueManagedDatabaseRequest request) {
        if (request != null && request.instanceId() != null) {
            return instanceRepository.findById(request.instanceId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.MANAGED_INSTANCE_NOT_FOUND));
        }
        List<ManagedInstance> active = instanceRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc();
        if (active.isEmpty()) {
            throw new BusinessException(ErrorCode.MANAGED_INSTANCE_NOT_FOUND,
                    "발급 가능한 활성 인스턴스가 없습니다 — 관리자에게 문의하세요");
        }
        return active.get(0);
    }

    /** 발급 순번은 워크스페이스 내 사용자 발급 수 + 1 — 인스턴스 내 이름 충돌(철회·타 워크스페이스 발급)은 검사로 건너뛴다 */
    private String nextSchemaName(long instanceId, long userId, long used) {
        long seq = used + 1;
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = "cf_u" + userId + "_d" + seq;
            if (databaseRepository.findByInstanceIdAndSchemaName(instanceId, candidate).isEmpty()) {
                return candidate;
            }
            seq++;
        }
        throw new BusinessException(ErrorCode.MANAGED_PROVISION_FAILED,
                "발급 스키마 이름을 정할 수 없습니다 — 관리자에게 문의하세요");
    }

    /** 스키마명 cf_u{userId}_d{seq}에서 seq 표기(_d 뒤)만 뽑는다 — 커넥션 표시 이름 접미 */
    private static String schemaSuffix(String schemaName) {
        int index = schemaName.lastIndexOf("_d");
        return schemaName.substring(index + 2);
    }

    /** 사용자 노출 주소 — publicHost가 있으면 그 값, 없으면 접속 host(하위 호환 폴백).
     *  프로비저닝·철회 등 서버 접속은 항상 내부 host를 쓴다 */
    private static String displayHost(ManagedInstance instance) {
        return instance.getPublicHost() != null ? instance.getPublicHost() : instance.getHost();
    }

    private ManagedDatabaseResponse toResponse(ManagedDatabase managed) {
        ManagedInstance instance = instanceRepository.findById(managed.getInstanceId()).orElse(null);
        DbConnection connection = connectionRepository.findById(managed.getConnectionId()).orElse(null);
        User creator = userRepository.findById(managed.getUserId()).orElse(null);
        UserRefResponse createdBy = creator == null
                ? null
                : new UserRefResponse(Long.toString(creator.getId()), creator.getName());
        return new ManagedDatabaseResponse(
                Long.toString(managed.getId()),
                Long.toString(managed.getInstanceId()),
                instance == null ? null : instance.getDisplayName(),
                managed.getSchemaName(),
                Long.toString(managed.getWorkspaceId()),
                Long.toString(managed.getConnectionId()),
                connection == null ? null : connection.getName(),
                createdBy,
                managed.getCreatedAt());
    }
}
