package net.java21.crowfoot.api.model.sync;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.service.SchemaIntrospectionService;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.edit.DocumentEditor;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DB → 문서 동기화 계획·적용 (08-core/02-model.md Section 1.16 — 규칙 05-editor/04-dbms-engineering.md §3.3).
 *
 * <p>계획은 문서의 원천 커넥션을 비교용 조립(introspectContentForComparison — DB에 있는 객체만)으로 읽어
 * {@link DocumentSync}로 병합해 본 변경 목록이다. 적용은 클라이언트가 본 계획의 지문을 받아 실행 시점에 다시 계산하고,
 * 지문이 같을 때만 병합 결과를 새 문서 버전으로 저장한다(마이그레이션 실행 1.15와 같은 "재계산 후 실행" 규칙).
 *
 * <p>바뀌는 것은 문서뿐이다 — 데이터베이스에는 읽기만 한다. 그래서 MCP 반영 허용(McpApplyGuard, 08-core/06-connection.md
 * Section 2.1)은 적용 조건이 아니다. 토큰 요청은 다른 문서 편집 API처럼 TokenScope의 워크스페이스 범위만 따른다.
 *
 * <p>트랜잭션을 열지 않는다 — 외부 JDBC 호출을 커넥션 점유 없이 끝낸 뒤 저장만 {@link DocumentSyncWriter}의 트랜잭션에서 한다.
 */
@Service
@RequiredArgsConstructor
public class DocumentSyncService {

    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;
    /** 변경 요약 항목 상한 — 문서 편집 API와 같다 */
    private static final int MAX_SUMMARY_ITEMS = 200;

    private final ModelRepository modelRepository;
    private final DbConnectionRepository connectionRepository;
    private final SchemaIntrospectionService schemaIntrospectionService;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final DocumentSyncWriter writer;

    /** 적용 요청 — planFingerprint는 계획 응답의 값, includeRemovals는 생략 시 false */
    public record ApplyRequest(String planFingerprint, Boolean includeRemovals) {
    }

    /** 계획 응답 (1.16.1) */
    public record PlanResponse(List<DocumentSync.Item> items, List<DocumentSync.Item> removals, int changeCount,
                               int removalCount, String planFingerprint, long version) {
    }

    /** 적용 응답 (1.16.2) — removals는 실제로 지운 것(includeRemovals=false면 빈 목록), skippedRemovals는 남긴 수 */
    public record ApplyResponse(boolean changed, String workspaceId, String modelId, long version,
                                List<DocumentSync.Item> items, List<DocumentSync.Item> removals, int changeCount,
                                int removalCount, int skippedRemovals) {
    }

    /** 계획 — Editor 이상(내부 DB 접속이라 스키마 조회 3.7과 같다). 아무것도 바꾸지 않는다 */
    public PlanResponse plan(long userId, long workspaceId, long modelId, long connectionId) {
        Computed computed = compute(userId, workspaceId, modelId, connectionId);
        DocumentSync.Result plan = computed.plan();
        return new PlanResponse(plan.items(), plan.removals(), plan.items().size(), plan.removals().size(),
                plan.fingerprint(), computed.model().getVersion());
    }

    /** 적용 — Editor 이상. 지문이 다르면 409 SYNC_PLAN_CHANGED, 바뀐 것이 없으면 버전을 올리지 않는다 */
    public ApplyResponse apply(long userId, long workspaceId, long modelId, long connectionId, ApplyRequest request) {
        if (request.planFingerprint() == null || request.planFingerprint().isBlank()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.sync.fingerprint-required");
        }
        boolean includeRemovals = Boolean.TRUE.equals(request.includeRemovals());
        Computed computed = compute(userId, workspaceId, modelId, connectionId);
        DocumentSync.Result plan = computed.plan();
        if (!plan.fingerprint().equals(request.planFingerprint())) {
            throw new BusinessException(ErrorCode.SYNC_PLAN_CHANGED);
        }
        DocumentSync.Result result = includeRemovals ? DocumentSync.sync(computed.document(), computed.db(), true) : plan;
        List<DocumentSync.Item> removed = includeRemovals ? result.removals() : List.of();
        Model model = computed.model();
        String ws = Long.toString(workspaceId);
        String id = Long.toString(modelId);
        if (result.items().isEmpty() && removed.isEmpty()) {
            return new ApplyResponse(false, ws, id, model.getVersion(), List.of(), List.of(), 0, 0, plan.removals().size());
        }

        String content = result.merged().toString();
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.model.size-exceeded");
        }
        long version = writer.save(workspaceId, modelId, model.getVersion(), content,
                changeSummary(connectionId, result.items(), removed), userId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("connectionId", Long.toString(connectionId));
        detail.put("version", version);
        detail.put("changes", result.items().size());
        detail.put("removals", removed.size());
        detail.put("includeRemovals", includeRemovals);
        auditRecorder.record(userId, "MODEL_SYNCED_FROM_DB", "MODEL", id, detail);
        return new ApplyResponse(true, ws, id, version, result.items(), removed, result.items().size(), removed.size(),
                includeRemovals ? 0 : plan.removals().size());
    }

    /* ---------- 내부 ---------- */

    private record Computed(Model model, JsonNode document, JsonNode db, DocumentSync.Result plan) {
    }

    /** 공용 계산 — 권한·원천 연결 확인 → DB 읽기 → 병합(삭제 없이). 계획과 적용이 같은 원천을 쓴다 */
    private Computed compute(long userId, long workspaceId, long modelId, long connectionId) {
        roleChecker.requireEditor(userId, workspaceId);
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
        DbConnection connection = connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
        // 동기화는 문서가 연결된 원천 커넥션과만 한다 — 다른 DB 구조로 문서를 덮어쓰는 사고를 막는다
        if (model.getSourceConnectionId() == null || model.getSourceConnectionId() != connectionId) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.sync.not-source-connection");
        }
        JsonNode document = read(model.getContent());
        JsonNode db;
        try {
            db = objectMapper.readTree(schemaIntrospectionService.introspectContentForComparison(connection));
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.REVERSE_FAILED);
        }
        return new Computed(model, document, db, DocumentSync.sync(document, db, false));
    }

    /** 본체를 읽는다 — 구조가 어긋난 문서는 고치지 않는다(문서 편집 API와 같은 기준) */
    private JsonNode read(String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.CONTENT_UNREADABLE);
        }
        // 1차 구현 이전의 빈 문서({"tables":[],"relationships":[]}) — 에디터가 열 때와 같이 지금 구조로 옮긴다
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

    /** 버전 기록의 변경 요약 — 구조 diff형(08-core/13-model-versions.md Section 1.1)에 출처(source: sync)를 더한다 */
    private String changeSummary(long connectionId, List<DocumentSync.Item> items, List<DocumentSync.Item> removed) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("source", "sync");
        summary.put("connectionId", Long.toString(connectionId));
        ArrayNode array = summary.putArray("items");
        List<DocumentSync.Item> all = new java.util.ArrayList<>(items);
        all.addAll(removed);
        all.stream().limit(MAX_SUMMARY_ITEMS).forEach(item -> array.addObject()
                .put("kind", item.kind())
                .put("action", item.action())
                .put("table", item.table())
                .put("name", item.name())
                .put("detail", item.detail()));
        summary.put("layoutOnly", false);
        summary.put("truncated", all.size() > MAX_SUMMARY_ITEMS);
        return summary.toString();
    }
}
