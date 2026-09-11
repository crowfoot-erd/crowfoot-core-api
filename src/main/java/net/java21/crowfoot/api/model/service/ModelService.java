package net.java21.crowfoot.api.model.service;

import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.dto.CreateModelRequest;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.ModelSummaryResponse;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.model.repository.ModelDiagramRepository;
import net.java21.crowfoot.api.model.repository.ModelQueryRepository;
import net.java21.crowfoot.api.model.repository.ModelQueryRepository.ModelRow;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * ERD 문서 실체 API (08-core/02-model.md Section 1) — 목록·상세·생성·메타 변경·삭제.
 * content 저장은 에디터 단계에서 구현한다.
 *
 * <p>생성 시 대표 다이어그램(main)을 같은 트랜잭션으로 자동 생성한다 —
 * 모델 진입 시 여는 화면이 항상 존재하도록 (06-erd/00-domain.md Section 3.10).
 */
@Service
@RequiredArgsConstructor
public class ModelService {

    /** 빈 Canonical 문서 (1.2 동작 — 빈 객체 직렬화) */
    private static final String EMPTY_CONTENT = "{\"tables\":[],\"relationships\":[]}";
    /** 빈 레이아웃 초기값 (2.3 상세 형태와 동일 구조) */
    private static final String EMPTY_LAYOUT = "{\"nodes\":[],\"edges\":[],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
    private static final String MAIN_DIAGRAM_NAME = "main";

    private final ModelRepository modelRepository;
    private final ModelDiagramRepository modelDiagramRepository;
    private final ModelQueryRepository modelQueryRepository;
    private final DatabaseTypeRepository databaseTypeRepository;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;

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
                request.canvasWidth(), request.canvasHeight(), EMPTY_CONTENT, userId));
        modelDiagramRepository.save(new ModelDiagram(model.getId(), MAIN_DIAGRAM_NAME, EMPTY_LAYOUT, true));
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
                model.getCanvasWidth(),
                model.getCanvasHeight(),
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
                row.canvasWidth(),
                row.canvasHeight(),
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
                model.getCanvasWidth(),
                model.getCanvasHeight(),
                model.getContent(),
                (int) model.getVersion(),
                createdBy,
                model.getCreatedAt(),
                model.getUpdatedAt());
    }
}
