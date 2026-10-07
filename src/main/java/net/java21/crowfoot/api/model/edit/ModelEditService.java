package net.java21.crowfoot.api.model.edit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.domaintype.domain.WorkspaceDomainType;
import net.java21.crowfoot.api.domaintype.repository.WorkspaceDomainTypeRepository;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.edit.EditRequests.DomainTypeRef;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.model.service.ModelVersionPruner;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 문서 편집 API — 문서 본체를 요구사항·테이블·관계 단위로 고친다 (08-core/17-model-edit.md).
 * 저장은 기존 저장(08-core/02-model.md Section 1.5)과 같은 경로다 — 한 트랜잭션에서 본체를 읽고, 고치고,
 * 버전을 1 올려 저장하고, 버전 스냅샷을 남긴다.
 */
@Service
@RequiredArgsConstructor
public class ModelEditService {

    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_NOTE_LENGTH = 500;
    /** 변경 요약 항목 상한 — 넘으면 truncated로 표시한다(웹 에디터의 요약과 같은 규칙) */
    private static final int MAX_SUMMARY_ITEMS = 200;

    private final ModelRepository modelRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final ModelVersionPruner modelVersionPruner;
    private final WorkspaceDomainTypeRepository domainTypeRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    /** 개요 조회(Viewer 이상) — 요구사항, 테이블, 관계, 그룹을 이름 기준으로 돌려준다 (Section 3.1) */
    @Transactional(readOnly = true)
    public Map<String, Object> outline(long userId, long workspaceId, long modelId) {
        roleChecker.requireMember(userId, workspaceId);
        Model model = find(workspaceId, modelId);
        JsonNode root = read(model);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelId", String.valueOf(model.getId()));
        out.put("name", model.getName());
        out.put("databaseType", model.getDatabaseType());
        out.put("version", model.getVersion());
        out.put("sourceConnectionId", model.getSourceConnectionId() == null ? null : String.valueOf(model.getSourceConnectionId()));
        out.putAll(DocumentOutline.build(root));
        attachRequirementChanges(out, root, model);
        return out;
    }

    /** 반영 대기 요구사항마다 마지막으로 반영한 내용과 지금 내용(Section 2.4) — 버전 기록 최근 50개에서 찾는다 */
    @SuppressWarnings("unchecked")
    private void attachRequirementChanges(Map<String, Object> out, JsonNode root, Model model) {
        Object items = out.get("requirements");
        if (!(items instanceof List<?> list) || list.stream().noneMatch(i -> "PENDING".equals(((Map<String, Object>) i).get("state")))) {
            return;
        }
        List<JsonNode> previous = new java.util.ArrayList<>();
        for (net.java21.crowfoot.api.model.domain.ModelVersion version
                : modelVersionRepository.findTop50ByModelIdOrderByVersionDesc(model.getId())) {
            try {
                previous.add(objectMapper.readTree(version.getContent()));
            } catch (RuntimeException e) {
                // 읽지 못한 버전은 건너뛴다
            }
        }
        Map<String, Map<String, Object>> changes = RequirementChanges.compute(root, previous);
        for (Object item : list) {
            Map<String, Object> requirement = (Map<String, Object>) item;
            Map<String, Object> change = changes.get(String.valueOf(requirement.get("code")));
            if (change != null) {
                requirement.put("changes", change);
            }
        }
    }

    /** 요구사항 반영(Editor 이상) (Section 3.2) */
    @Transactional
    public EditResult applyRequirements(long userId, long workspaceId, long modelId, EditRequests.RequirementsApply request) {
        return edit(userId, workspaceId, modelId, request.baseVersion(), request.note(), "MODEL_REQUIREMENTS_APPLIED",
                editor -> editor.applyRequirements(request.items()));
    }

    /** 스키마 반영(Editor 이상) — 테이블·컬럼·키·관계·그룹, 근거 요구사항 연결. 지우지 않는다 (Section 3.3) */
    @Transactional
    public EditResult applySchema(long userId, long workspaceId, long modelId, EditRequests.SchemaApply request) {
        return edit(userId, workspaceId, modelId, request.baseVersion(), request.note(), "MODEL_SCHEMA_APPLIED",
                editor -> editor.applySchema(request.tables(), request.relationships(), request.areas()));
    }

    /** 삭제(Editor 이상) — 테이블·컬럼·관계·요구사항 (Section 3.4) */
    @Transactional
    public EditResult remove(long userId, long workspaceId, long modelId, EditRequests.SchemaRemove request) {
        return edit(userId, workspaceId, modelId, request.baseVersion(), request.note(), "MODEL_SCHEMA_REMOVED",
                editor -> editor.remove(request.tables(), request.columns(), request.relationships(), request.requirements(), request.checks()));
    }

    /** 요구사항 동기화 계획 응답 (Section 3.5) */
    public record RequirementsSyncPlan(String workspaceId, String modelId, long documentVersion,
                                       List<RequirementSync.Added> added, List<RequirementSync.Updated> updated,
                                       List<RequirementSync.Missing> missing, int unchanged, int changeCount,
                                       String planFingerprint) {
    }

    /** 요구사항 동기화 적용 응답 — 편집 결과와 계획 건수 */
    public record RequirementsSyncResult(EditResult result, int added, int updated, int dropped, int removed) {
    }

    /** 요구사항 동기화 계획(Editor 이상) — 문서를 바꾸지 않는다. 검증 오류는 400으로 알린다 (Section 3.5) */
    @Transactional(readOnly = true)
    public RequirementsSyncPlan planRequirementsSync(long userId, long workspaceId, long modelId, EditRequests.RequirementsSync request) {
        roleChecker.requireEditor(userId, workspaceId);
        Model model = find(workspaceId, modelId);
        RequirementSync.Plan plan = syncPlan(model, workspaceId, request);
        return new RequirementsSyncPlan(String.valueOf(workspaceId), String.valueOf(modelId), model.getVersion(),
                plan.added(), plan.updated(), plan.missing(), plan.unchanged(), plan.changeCount(), plan.fingerprint());
    }

    /** 요구사항 동기화 적용(Editor 이상) — 지문이 다르면 409 REQUIREMENTS_SYNC_PLAN_CHANGED (Section 3.5) */
    @Transactional
    public RequirementsSyncResult applyRequirementsSync(long userId, long workspaceId, long modelId, EditRequests.RequirementsSync request) {
        roleChecker.requireEditor(userId, workspaceId);
        if (request.planFingerprint() == null || request.planFingerprint().isBlank()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.model-edit.plan-fingerprint");
        }
        Model model = find(workspaceId, modelId);
        RequirementSync.Plan plan = syncPlan(model, workspaceId, request);
        if (!plan.fingerprint().equals(request.planFingerprint())) {
            throw new BusinessException(ErrorCode.REQUIREMENTS_SYNC_PLAN_CHANGED);
        }
        // 계획을 계산한 버전에 그대로 쓴다 — 그 사이에 저장이 끼어들었으면 409 VERSION_CONFLICT
        EditResult result = edit(userId, workspaceId, modelId, model.getVersion(), request.note(), "MODEL_REQUIREMENTS_SYNCED",
                editor -> applySyncPlan(editor, plan));
        return new RequirementsSyncResult(result, plan.added().size(), plan.updated().size(), plan.toDrop().size(),
                plan.toRemove().size());
    }

    private RequirementSync.Plan syncPlan(Model model, long workspaceId, EditRequests.RequirementsSync request) {
        if (request.items() == null || request.items().isEmpty()) {
            // 빈 목록은 모든 요구사항을 빠짐으로 만든다 — 실수로 비우지 않게 막는다
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.model-edit.sync-items");
        }
        ObjectNode root = (ObjectNode) read(model);
        RequirementSync.Plan plan = RequirementSync.plan(root, request.items(), Boolean.TRUE.equals(request.acceptRemovals()));
        // 미리 적용해 본다 — 검증(제목 길이, 없는 테이블 등)은 save_requirements와 같고 오류 위치는 items[n]이다
        DocumentEditor dryRun = new DocumentEditor(root.deepCopy(), model.getDatabaseType(), domainTypes(workspaceId));
        try {
            applySyncPlan(dryRun, plan);
        } catch (DocumentEditor.RequirementLimitException e) {
            throw new BusinessException(ErrorCode.REQUIREMENT_LIMIT_EXCEEDED);
        }
        dryRun.throwIfInvalid();
        return plan;
    }

    private static void applySyncPlan(DocumentEditor editor, RequirementSync.Plan plan) {
        editor.applyRequirements(plan.resolvedItems());
        if (!plan.toDrop().isEmpty()) {
            editor.applyRequirements(plan.toDrop().stream()
                    .map(code -> new EditRequests.RequirementItem(code, null, null, "dropped", null, null, null)).toList());
        }
        if (!plan.toRemove().isEmpty()) {
            editor.remove(null, null, null, plan.toRemove());
        }
    }

    private EditResult edit(long userId, long workspaceId, long modelId, Long baseVersion, String note, String auditAction,
                            Consumer<DocumentEditor> action) {
        roleChecker.requireEditor(userId, workspaceId);
        Model model = find(workspaceId, modelId);
        if (baseVersion != null && baseVersion != model.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.model-edit.note-length");
        }
        ObjectNode root = (ObjectNode) read(model);
        String before = root.toString();
        DocumentEditor editor = new DocumentEditor(root, model.getDatabaseType(), domainTypes(workspaceId));
        try {
            action.accept(editor);
        } catch (DocumentEditor.RequirementLimitException e) {
            throw new BusinessException(ErrorCode.REQUIREMENT_LIMIT_EXCEEDED);
        }
        editor.throwIfInvalid();

        String content = root.toString();
        long version = model.getVersion();
        boolean changed = !content.equals(before);
        if (changed) {
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.model.size-exceeded");
            }
            Instant now = Instant.now();
            // 조건부 UPDATE — 읽은 뒤에 다른 저장이 끼어들었으면 0건이다(낙관적 잠금)
            int updated = modelRepository.updateContentIfVersionMatches(modelId, workspaceId, version, content, now);
            if (updated == 0) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            }
            version = version + 1;
            modelVersionRepository.save(new ModelVersion(modelId, version, content, changeSummary(editor.changes()),
                    note == null || note.isBlank() ? null : note, userId, now));
            modelVersionPruner.prune(modelId, version, userId);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("version", version);
            editor.changes().stream().map(change -> change.kind() + "." + change.action())
                    .distinct().forEach(key -> detail.put(key, editor.changes().stream()
                            .filter(change -> (change.kind() + "." + change.action()).equals(key)).count()));
            auditRecorder.record(userId, auditAction, "MODEL", Long.toString(modelId), detail);
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        for (DocumentEditor.Change change : editor.changes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", change.kind());
            item.put("action", change.action());
            item.put("name", change.table().isEmpty() || change.table().equals(change.name()) ? change.name() : change.table() + "." + change.name());
            summary.add(item);
        }
        return new EditResult(version, changed, summary, editor.warnings(), DocumentOutline.requirementSummary(root));
    }

    /** 쓰기 3종의 공통 응답 (Section 3) */
    public record EditResult(long version, boolean changed, List<Map<String, Object>> summary,
                             List<DocumentEditor.Warning> warnings, Map<String, Object> requirements) {
    }

    private Model find(long workspaceId, long modelId) {
        return modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
    }

    /** 본체를 읽는다 — 구조가 어긋난 문서는 편집 API로 고치지 않는다 */
    private JsonNode read(Model model) {
        JsonNode root;
        try {
            root = objectMapper.readTree(model.getContent());
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.CONTENT_UNREADABLE);
        }
        // 1차 구현 이전의 문서({"tables":[],"relationships":[]}) — 에디터가 열 때와 같이 지금 구조로 옮긴다
        if (root.isObject() && !root.has("model") && root.path("tables").isArray() && root.path("tables").isEmpty()) {
            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("schemaVersion", 1);
            ObjectNode modelNode = normalized.putObject("model");
            modelNode.putArray("tables");
            modelNode.putArray("relationships");
            ObjectNode diagram = normalized.putObject("diagram");
            diagram.putObject("nodes");
            diagram.putArray("notes");
            diagram.putNull("viewport");
            root = normalized;
        }
        if (!DocumentEditor.readable(root)) {
            throw new BusinessException(ErrorCode.CONTENT_UNREADABLE);
        }
        return root;
    }

    /** 워크스페이스 도메인 타입 — 컬럼 입력의 domainType 이름(대소문자 무시)으로 찾는다 */
    private Map<String, DomainTypeRef> domainTypes(long workspaceId) {
        Map<String, DomainTypeRef> out = new HashMap<>();
        for (WorkspaceDomainType type : domainTypeRepository.findByWorkspaceIdOrderByNameAsc(workspaceId)) {
            out.put(type.getName().toLowerCase(Locale.ROOT), new DomainTypeRef(String.valueOf(type.getId()), type.getName(),
                    type.getVersion(), type.getDataType(), type.getLength(), type.getPrecision(), type.getScale(),
                    type.isNullable(), type.getDefaultValue()));
        }
        return out;
    }

    /** 버전 기록의 변경 요약 — 웹 에디터와 같은 구조 diff형이다 (08-core/13-model-versions.md Section 1.1) */
    private String changeSummary(List<DocumentEditor.Change> changes) {
        ObjectNode summary = objectMapper.createObjectNode();
        var items = summary.putArray("items");
        changes.stream().limit(MAX_SUMMARY_ITEMS).forEach(change -> {
            ObjectNode item = items.addObject();
            item.put("kind", change.kind());
            item.put("action", change.action());
            item.put("table", change.table());
            item.put("name", change.name());
            item.put("detail", "");
        });
        summary.put("layoutOnly", false);
        summary.put("truncated", changes.size() > MAX_SUMMARY_ITEMS);
        return summary.toString();
    }
}
