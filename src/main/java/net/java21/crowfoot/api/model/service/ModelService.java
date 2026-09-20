package net.java21.crowfoot.api.model.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.dto.CreateModelRequest;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.ModelSummaryResponse;
import net.java21.crowfoot.api.model.dto.ModelVersionResponse;
import net.java21.crowfoot.api.model.dto.SaveContentRequest;
import net.java21.crowfoot.api.model.dto.SaveContentResponse;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.model.repository.ModelDiagramRepository;
import net.java21.crowfoot.api.model.repository.ModelQueryRepository;
import net.java21.crowfoot.api.model.repository.ModelQueryRepository.ModelRow;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ERD 문서 실체 API (08-core/02-model.md Section 1) — 목록·상세·생성·메타 변경·삭제·content 저장.
 *
 * <p>생성 시 대표 다이어그램(main)을 같은 트랜잭션으로 자동 생성한다 —
 * 모델 진입 시 여는 화면이 항상 존재하도록 (06-erd/00-domain.md Section 3.10).
 */
@Service
@RequiredArgsConstructor
public class ModelService {

    /** 빈 Canonical 문서 v1 (1.2 동작 — schemaVersion 1, 08-core/02-model.md Section 1.5.1) */
    private static final String EMPTY_CONTENT =
            "{\"schemaVersion\":1,\"model\":{\"tables\":[],\"relationships\":[]},\"diagram\":{\"nodes\":{},\"notes\":[],\"viewport\":null}}";
    /** 빈 레이아웃 초기값 (2.3 상세 형태와 동일 구조) */
    private static final String EMPTY_LAYOUT = "{\"nodes\":[],\"edges\":[],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
    private static final String MAIN_DIAGRAM_NAME = "main";
    /** content 상한 — UTF-8 바이트 기준 5MB (1.5) */
    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;
    /** 변경 요약 상한 — UTF-8 바이트 기준 64KB (1.11) — 웹 diff 상한(항목 50)보다 여유 있게 */
    private static final int MAX_CHANGE_SUMMARY_BYTES = 64 * 1024;

    private final ModelRepository modelRepository;
    private final ModelDiagramRepository modelDiagramRepository;
    private final ModelQueryRepository modelQueryRepository;
    private final DatabaseTypeRepository databaseTypeRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final ModelVersionPruner modelVersionPruner;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    /** 목록(Viewer 이상) — keyword·offset 페이징, 정렬 updatedAt desc */
    @Transactional(readOnly = true)
    public ListApiResponse<ModelSummaryResponse> list(long userId, long workspaceId, String keyword, Integer page, Integer size) {
        roleChecker.requireMember(userId, workspaceId);
        String trimmed = keyword == null ? null : keyword.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = size == null || size < 1 ? 20 : Math.min(size, 100);

        long totalCount = modelQueryRepository.count(workspaceId, trimmed);
        if (totalCount == 0) {
            return ListApiResponse.paged(List.of(), normalizedPage, normalizedSize, 0);
        }
        List<ModelSummaryResponse> responses = modelQueryRepository
                .search(workspaceId, trimmed, normalizedPage, normalizedSize)
                .stream()
                .map(ModelService::toSummary)
                .toList();
        return ListApiResponse.paged(responses, normalizedPage, normalizedSize, totalCount);
    }

    /** 상세(Viewer 이상 — 1.3) — 에디터 로드용, content를 통째로 내려준다 */
    @Transactional(readOnly = true)
    public ModelResponse detail(long userId, long workspaceId, long modelId) {
        roleChecker.requireMember(userId, workspaceId);
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
        return toResponse(model);
    }

    /** 버전 경량 조회(Viewer 이상 — 1.9 협업 폴링) — content를 로드하지 않는 프로젝션 */
    @Transactional(readOnly = true)
    public ModelVersionResponse version(long userId, long workspaceId, long modelId) {
        roleChecker.requireMember(userId, workspaceId);
        List<Object[]> rows = modelRepository.findVersionRowByIdAndWorkspaceId(modelId, workspaceId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND);
        }
        Object[] row = rows.get(0);
        return new ModelVersionResponse(((Number) row[0]).intValue(), (Instant) row[1]);
    }

    /** 생성(Editor 이상) — main 다이어그램 자동 생성, 이름 중복은 409, 비활성 databaseType은 400 */
    @Transactional
    public ModelResponse create(long userId, long workspaceId, CreateModelRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        if (databaseTypeRepository.findByCodeAndIsActiveTrue(request.databaseType()).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 데이터베이스 종류입니다");
        }
        if (modelRepository.existsByWorkspaceIdAndName(workspaceId, request.name())) {
            throw new BusinessException(ErrorCode.DUPLICATED_NAME);
        }

        Model model = modelRepository.save(new Model(
                workspaceId, request.name(), request.description(), request.databaseType(),
                EMPTY_CONTENT, userId));
        modelDiagramRepository.save(new ModelDiagram(model.getId(), MAIN_DIAGRAM_NAME, EMPTY_LAYOUT, true));
        // v0 스냅샷 — changeSummary 없음(빈 문서): 웹이 "문서 생성"으로 렌더 (1.11)
        modelVersionRepository.save(new ModelVersion(model.getId(), model.getVersion(),
                EMPTY_CONTENT, null, null, userId, model.getCreatedAt()));
        modelVersionPruner.prune(model.getId(), model.getVersion(), userId);
        auditRecorder.record(userId, "MODEL_CREATED", "MODEL",
                Long.toString(model.getId()), Map.of(
                        "name", model.getName(),
                        "databaseType", model.getDatabaseType()));
        return toResponse(model);
    }

    /** 삭제(Owner만 — 08-core/02-model.md Section 1.6) — 물리 삭제, 소속 다이어그램은 DB CASCADE */
    @Transactional
    public void delete(long userId, long workspaceId, long modelId) {
        roleChecker.requireOwner(userId, workspaceId);
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
        modelRepository.deleteById(model.getId());
        auditRecorder.record(userId, "MODEL_DELETED", "MODEL",
                Long.toString(model.getId()), Map.of("name", model.getName()));
    }

    /**
     * 메타 변경(Editor 이상 — 1.4) — 이름·설명.
     * PATCH 의미론: 필드 생략은 변경 없음, description 명시적 null은 클리어.
     * 메타 변경은 version을 올리지 않는다 — 저장 버전은 문서 본체(content) 변경에만 붙는다.
     */
    @Transactional
    public ModelSummaryResponse patch(long userId, long workspaceId, long modelId, JsonNode body) {
        roleChecker.requireEditor(userId, workspaceId);
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));

        if (body.has("name") && !body.get("name").isNull()) {
            String name = body.get("name").asText();
            if (name.isBlank() || name.length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이름은 1~100자여야 합니다");
            }
            if (!name.equals(model.getName())
                    && modelRepository.existsByWorkspaceIdAndNameAndIdNot(workspaceId, name, modelId)) {
                throw new BusinessException(ErrorCode.DUPLICATED_NAME);
            }
            model.setName(name);
        }
        if (body.has("description")) {
            model.setDescription(body.get("description").isNull() ? null : body.get("description").asText());
        }

        auditRecorder.record(userId, "MODEL_UPDATED", "MODEL",
                Long.toString(model.getId()), null);
        return toSummary(model);
    }

    /**
     * 문서 본체 저장(Editor 이상 — 1.5) — 문서 단위 통짜 저장 + 낙관적 잠금.
     * 검증은 JSON 구문 파싱 가능·UTF-8 5MB 상한만 — Canonical 스키마 해석은 에디터가 담당한다(1.5.1).
     * version 일치 조건부 UPDATE(원자적)로 0행이면 409 VERSION_CONFLICT.
     * 저장이 성공하면 같은 트랜잭션에서 버전 스냅샷을 1:1로 남긴다(1.11) — changeSummary는
     * 웹이 만든 변경 요약 JSON을 가드(64KB·JSON 구문) 후 해석 없이 보관한다.
     */
    @Transactional
    public SaveContentResponse saveContent(long userId, long workspaceId, long modelId, SaveContentRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        if (modelRepository.findByIdAndWorkspaceId(modelId, workspaceId).isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND);
        }
        String content = request.content();
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "문서 크기가 상한(5MB)을 초과했습니다");
        }
        try {
            objectMapper.readTree(content);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "content는 유효한 JSON이어야 합니다");
        }
        String changeSummary = requireValidChangeSummary(request.changeSummary());

        Instant now = Instant.now();
        int updated = modelRepository.updateContentIfVersionMatches(modelId, workspaceId,
                request.baseVersion(), content, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
        // 스냅샷 createdAt은 bulk UPDATE가 기록한 models.updated_at과 같은 시각(1.11 정합 규칙)
        modelVersionRepository.save(new ModelVersion(modelId, model.getVersion(),
                content, changeSummary, null, userId, now));
        modelVersionPruner.prune(modelId, model.getVersion(), userId);
        auditRecorder.record(userId, "MODEL_UPDATED", "MODEL",
                Long.toString(modelId), Map.of(
                        "baseVersion", request.baseVersion(),
                        "version", model.getVersion()));
        return new SaveContentResponse((int) model.getVersion(), model.getUpdatedAt());
    }

    /** 변경 요약 가드(1.11) — null 통과, 64KB 상한·JSON 구문만 본다(내용 해석은 클라이언트 소유) */
    private String requireValidChangeSummary(String changeSummary) {
        if (changeSummary == null) {
            return null;
        }
        if (changeSummary.getBytes(StandardCharsets.UTF_8).length > MAX_CHANGE_SUMMARY_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "변경 요약이 상한(64KB)을 초과했습니다");
        }
        try {
            objectMapper.readTree(changeSummary);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "changeSummary는 유효한 JSON이어야 합니다");
        }
        return changeSummary;
    }

    private ModelSummaryResponse toSummary(Model model) {
        User creator = userRepository.findById(model.getCreatedBy()).orElse(null);
        UserRefResponse createdBy = creator == null
                ? null
                : new UserRefResponse(Long.toString(creator.getId()), creator.getName());
        return new ModelSummaryResponse(
                Long.toString(model.getId()),
                Long.toString(model.getWorkspaceId()),
                model.getName(),
                model.getDescription(),
                model.getDatabaseType(),
                model.getSourceConnectionId() == null ? null : Long.toString(model.getSourceConnectionId()),
                (int) model.getVersion(),
                createdBy,
                model.getCreatedAt(),
                model.getUpdatedAt());
    }

    private static ModelSummaryResponse toSummary(ModelRow row) {
        UserRefResponse createdBy = row.createdById() == null
                ? null
                : new UserRefResponse(Long.toString(row.createdById()), row.createdByName());
        return new ModelSummaryResponse(
                Long.toString(row.id()),
                Long.toString(row.workspaceId()),
                row.name(),
                row.description(),
                row.databaseType(),
                row.sourceConnectionId() == null ? null : Long.toString(row.sourceConnectionId()),
                (int) row.version(),
                createdBy,
                row.createdAt(),
                row.updatedAt());
    }

    private ModelResponse toResponse(Model model) {
        User creator = userRepository.findById(model.getCreatedBy()).orElse(null);
        UserRefResponse createdBy = creator == null
                ? null
                : new UserRefResponse(Long.toString(creator.getId()), creator.getName());
        return new ModelResponse(
                Long.toString(model.getId()),
                Long.toString(model.getWorkspaceId()),
                model.getName(),
                model.getDescription(),
                model.getDatabaseType(),
                model.getSourceConnectionId() == null ? null : Long.toString(model.getSourceConnectionId()),
                model.getContent(),
                (int) model.getVersion(),
                createdBy,
                model.getCreatedAt(),
                model.getUpdatedAt());
    }
}
