package net.java21.crowfoot.api.domaintype.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.domaintype.domain.WorkspaceDomainType;
import net.java21.crowfoot.api.domaintype.dto.DomainTypeRequest;
import net.java21.crowfoot.api.domaintype.dto.DomainTypeResponse;
import net.java21.crowfoot.api.domaintype.repository.WorkspaceDomainTypeRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 워크스페이스 도메인 타입 API (08-core/16-domain-type.md) — 목록·만들기·고치기·지우기.
 *
 * <p>권한: 목록은 멤버 전체(Viewer도 컬럼에 붙은 도메인 타입 이름을 본다), 쓰기는 Editor 이상.
 * 서버는 문서를 고치지 않는다 — 도메인 타입을 고치거나 지워도 문서 내용은 그대로이고,
 * 문서에 반영하는 일은 그 문서를 연 편집자가 에디터에서 확인하고 한다(05-editor/01-core.md Section 11.1).
 */
@Service
@RequiredArgsConstructor
public class DomainTypeService {

    /** 워크스페이스당 도메인 타입 상한 */
    static final int MAX_DOMAIN_TYPES_PER_WORKSPACE = 200;

    private final WorkspaceDomainTypeRepository domainTypeRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;

    /** 목록(멤버 전체 — 3.1) — 이름 오름차순 */
    @Transactional(readOnly = true)
    public List<DomainTypeResponse> list(long userId, long workspaceId) {
        roleChecker.requireMember(userId, workspaceId);
        return domainTypeRepository.findByWorkspaceIdOrderByNameAsc(workspaceId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** 만들기(Editor 이상 — 3.2) — 이름 중복·상한을 본다. version은 1 */
    @Transactional
    public DomainTypeResponse create(long userId, long workspaceId, DomainTypeRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        String name = request.name().trim();
        if (domainTypeRepository.findByWorkspaceIdAndNameIgnoreCase(workspaceId, name).isPresent()) {
            throw new BusinessException(ErrorCode.DUPLICATED_NAME);
        }
        if (domainTypeRepository.countByWorkspaceId(workspaceId) >= MAX_DOMAIN_TYPES_PER_WORKSPACE) {
            throw new BusinessException(ErrorCode.DOMAIN_TYPE_LIMIT_EXCEEDED);
        }
        WorkspaceDomainType entity = new WorkspaceDomainType(workspaceId, name, userId);
        apply(entity, request);
        WorkspaceDomainType saved = domainTypeRepository.save(entity);
        auditRecorder.record(userId, "WORKSPACE_DOMAIN_TYPE_CREATED", "WORKSPACE",
                Long.toString(workspaceId), Map.of("name", name, "dataType", saved.getDataType()));
        return toResponse(saved);
    }

    /**
     * 고치기(Editor 이상 — 3.3) — baseVersion이 지금 버전과 다르면 VERSION_CONFLICT.
     * 값이 하나도 바뀌지 않았으면 version을 올리지 않고 그대로 돌려준다(감사도 남기지 않는다).
     */
    @Transactional
    public DomainTypeResponse update(long userId, long workspaceId, long domainTypeId, DomainTypeRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        WorkspaceDomainType entity = find(workspaceId, domainTypeId);
        if (request.baseVersion() == null) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.domain-type.base-version.required");
        }
        if (request.baseVersion() != entity.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        String name = request.name().trim();
        domainTypeRepository.findByWorkspaceIdAndNameIgnoreCase(workspaceId, name)
                .filter((other) -> !Objects.equals(other.getId(), entity.getId()))
                .ifPresent((other) -> {
                    throw new BusinessException(ErrorCode.DUPLICATED_NAME);
                });

        DomainTypeResponse before = toResponse(entity);
        entity.setName(name);
        apply(entity, request);
        if (sameValues(before, entity)) {
            return before;
        }
        int previousVersion = entity.getVersion();
        entity.setVersion(previousVersion + 1);
        WorkspaceDomainType saved = domainTypeRepository.save(entity);
        auditRecorder.record(userId, "WORKSPACE_DOMAIN_TYPE_UPDATED", "WORKSPACE",
                Long.toString(workspaceId),
                Map.of("name", name, "fromVersion", previousVersion, "toVersion", saved.getVersion()));
        return toResponse(saved);
    }

    /** 지우기(Editor 이상 — 3.4) — 쓰고 있는 문서가 있어도 지운다(서버는 어느 문서가 쓰는지 알지 못한다) */
    @Transactional
    public void delete(long userId, long workspaceId, long domainTypeId) {
        roleChecker.requireEditor(userId, workspaceId);
        WorkspaceDomainType entity = find(workspaceId, domainTypeId);
        domainTypeRepository.delete(entity);
        auditRecorder.record(userId, "WORKSPACE_DOMAIN_TYPE_DELETED", "WORKSPACE",
                Long.toString(workspaceId), Map.of("name", entity.getName()));
    }

    /** 소속 워크스페이스 검증 — 다른 워크스페이스의 id는 없는 것으로 본다 */
    private WorkspaceDomainType find(long workspaceId, long domainTypeId) {
        return domainTypeRepository.findById(domainTypeId)
                .filter((domainType) -> Objects.equals(domainType.getWorkspaceId(), workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.DOMAIN_TYPE_NOT_FOUND));
    }

    /** 요청 값을 엔티티에 싣는다 — 빈 문자열은 null로 정리한다 */
    private void apply(WorkspaceDomainType entity, DomainTypeRequest request) {
        entity.setDataType(request.dataType());
        entity.setLength(request.length());
        entity.setPrecision(request.precision());
        entity.setScale(request.scale());
        entity.setNullable(request.nullable() == null || request.nullable());
        entity.setDefaultValue(blankToNull(request.defaultValue()));
        entity.setDescription(blankToNull(request.description()));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean sameValues(DomainTypeResponse before, WorkspaceDomainType after) {
        return Objects.equals(before.name(), after.getName())
                && Objects.equals(before.dataType(), after.getDataType())
                && Objects.equals(before.length(), after.getLength())
                && Objects.equals(before.precision(), after.getPrecision())
                && Objects.equals(before.scale(), after.getScale())
                && before.nullable() == after.isNullable()
                && Objects.equals(before.defaultValue(), after.getDefaultValue())
                && Objects.equals(before.description(), after.getDescription());
    }

    private DomainTypeResponse toResponse(WorkspaceDomainType domainType) {
        return new DomainTypeResponse(
                Long.toString(domainType.getId()),
                Long.toString(domainType.getWorkspaceId()),
                domainType.getName(),
                domainType.getDataType(),
                domainType.getLength(),
                domainType.getPrecision(),
                domainType.getScale(),
                domainType.isNullable(),
                domainType.getDefaultValue(),
                domainType.getDescription(),
                domainType.getVersion(),
                domainType.getUpdatedAt());
    }
}
