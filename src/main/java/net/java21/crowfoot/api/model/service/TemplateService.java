package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.config.AppProperties;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.dto.CloneFromTemplateRequest;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.TemplateSummaryResponse;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.model.repository.ModelDiagramRepository;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.term.domain.WorkspaceTerm;
import net.java21.crowfoot.api.term.repository.WorkspaceTermRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 템플릿 API (08-core/09-templates.md) — 템플릿 워크스페이스(설정 {@code crowfoot.template.workspace-id})의
 * 문서를 공개 목록으로 내려주고, 그중 하나를 대상 워크스페이스의 새 문서로 복제한다.
 *
 * <p>템플릿은 신규 도메인이 아니라 기존 Model의 큐레이션 뷰다 — 목록의 활성 공유 조인은 공유 갤러리
 * ({@code ShareService.gallery()})와 같은 수집 규칙(활성만·문서당 최근 1건)을 따르고, 복제의 저장
 * 시퀀스는 SQL 가져오기({@code SqlImportService.importDocument})를 승계한다(한 트랜잭션에서 content까지,
 * main 다이어그램·v0 스냅샷·버전 pruner·감사). 차이는 원천이 DDL 텍스트가 아니라 템플릿 문서라는 것뿐이다.
 * 복제는 템플릿 워크스페이스의 도메인 표준 사전(workspace_terms)도 문서에 실제 쓰인 물리명만큼
 * 함께 데려온다 — 복제자는 스키마와 그 도메인 용어 패키지를 함께 받는다(Section 2.3).
 *
 * <p>존재 은닉(무인증 목록의 원칙): 설정이 없으면(프로퍼티 null) 빈 목록으로 응답한다 — 템플릿
 * 워크스페이스의 존재를 500·404로 노출하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TemplateService {

    private static final String EMPTY_LAYOUT =
            "{\"nodes\":[],\"edges\":[],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
    private static final String MAIN_DIAGRAM_NAME = "main";
    /** 워크스페이스 사전 상한(TermService와 같은 값) — 사전 동반 복사도 같은 한도 안에서 띄운다 */
    private static final int TERM_LIMIT_PER_WORKSPACE = 1000;

    private final ModelRepository modelRepository;
    private final ModelDiagramRepository modelDiagramRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final ModelShareRepository shareRepository;
    private final WorkspaceTermRepository termRepository;
    private final ModelVersionPruner modelVersionPruner;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final DatabaseTypeRepository databaseTypeRepository;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    /** 공개 목록(무인증) — 템플릿 워크스페이스 문서 전체를 갱신순으로. 설정 없으면 빈 목록(존재 은닉). */
    @Transactional(readOnly = true)
    public List<TemplateSummaryResponse> list() {
        Long templateWorkspaceId = templateWorkspaceId();
        if (templateWorkspaceId == null) {
            return List.of();
        }
        List<Model> models = modelRepository.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(templateWorkspaceId);
        if (models.isEmpty()) {
            return List.of();
        }
        Set<Long> modelIds = models.stream().map(Model::getId).collect(Collectors.toSet());
        Map<Long, ModelShare> latestShareByModel = latestActiveShareByModel(modelIds);
        return models.stream()
                .map(model -> {
                    ModelShare share = latestShareByModel.get(model.getId());
                    Counts counts = parseCounts(model.getContent());
                    return new TemplateSummaryResponse(
                            Long.toString(model.getId()),
                            model.getName(),
                            model.getDescription(),
                            model.getDatabaseType(),
                            counts.tables(),
                            counts.relationships(),
                            share == null ? null : share.getShareToken(),
                            model.getUpdatedAt());
                })
                .toList();
    }

    /** 복제(Editor 이상) — 템플릿 문서 하나를 대상 워크스페이스의 새 문서로 통째로 복사한다. */
    @Transactional
    public ModelResponse clone(long userId, long workspaceId, CloneFromTemplateRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        Long templateWorkspaceId = templateWorkspaceId();
        // 템플릿 기능이 꺼져 있어도 존재 은닉 — 대상 없음과 같은 404로 응답한다
        Model source = templateWorkspaceId == null ? null
                : modelRepository.findByIdAndWorkspaceId(request.templateModelId(), templateWorkspaceId)
                        .orElse(null);
        if (source == null) {
            throw new BusinessException(ErrorCode.TEMPLATE_NOT_FOUND);
        }
        requireActiveDatabaseType(source.getDatabaseType());
        String name = cloneName(request, source);
        if (modelRepository.existsByWorkspaceIdAndName(workspaceId, name)) {
            throw BusinessException.of(ErrorCode.DUPLICATED_NAME, "detail.doc-name.duplicated");
        }
        Counts counts = parseCounts(source.getContent());

        Model model = modelRepository.save(new Model(workspaceId, name, source.getDescription(),
                source.getDatabaseType(), source.getContent(), userId));
        // sourceConnectionId는 남기지 않는다 — 원본과 DB 커넥션은 무관하다(SQL 가져오기와 같은 규칙)
        modelDiagramRepository.save(new ModelDiagram(model.getId(), MAIN_DIAGRAM_NAME, EMPTY_LAYOUT, true));
        // v0 스냅샷 — 템플릿 복제로 태어난 문서의 요약(웹이 source:"template"을 인식해 배지를 렌더)
        modelVersionRepository.save(new ModelVersion(model.getId(), model.getVersion(),
                source.getContent(), templateSummary(source, counts), null, userId, model.getCreatedAt()));
        modelVersionPruner.prune(model.getId(), model.getVersion(), userId);
        // 도메인 표준 사전 동반 — 복제 문서에 실제 쓰인 물리명의 용어만 (09-templates.md Section 2.3)
        int copiedTerms = copyTemplateTerms(userId, workspaceId, templateWorkspaceId, source.getContent());
        auditRecorder.record(userId, "MODEL_CREATED_FROM_TEMPLATE", "MODEL",
                Long.toString(model.getId()), Map.of(
                        "templateModelId", Long.toString(source.getId()),
                        "name", name,
                        "databaseType", source.getDatabaseType(),
                        "tables", counts.tables(),
                        "relationships", counts.relationships(),
                        "terms", copiedTerms));
        return toResponse(model);
    }

    /**
     * 도메인 표준 사전 동반 (09-templates.md Section 2.3) — 템플릿 워크스페이스의 표준 사전 항목 중
     * **복제 문서 content에 실제 쓰인 물리명(테이블·컬럼)과 일치하는 것만** 대상 워크스페이스로 복사한다.
     * 그 도메인 어휘만 좁혀 주는 필터이자, 대상 워크스페이스의 다른 문서 도메인에 간섭하지 않는 가드다.
     * 대상에 이미 있는 용어는 복제자의 것을 우선한다(덮어쓰지 않는다), 상한({@value TERM_LIMIT_PER_WORKSPACE})도 지킨다.
     * 복사한 만큼을 돌려준다(감사 detail 원료) — 실패해도 복제 자체는 성공이다(사전은 부속물).
     */
    private int copyTemplateTerms(long userId, long workspaceId, long templateWorkspaceId, String content) {
        Set<String> physicalNames = physicalNames(content);
        if (physicalNames.isEmpty()) {
            return 0;
        }
        Set<String> existing = termRepository.findByWorkspaceIdOrderByTermAsc(workspaceId).stream()
                .map(WorkspaceTerm::getTerm)
                .collect(Collectors.toSet());
        int room = TERM_LIMIT_PER_WORKSPACE - existing.size();
        if (room <= 0) {
            return 0;
        }
        List<WorkspaceTerm> copies = new ArrayList<>();
        for (WorkspaceTerm term : termRepository.findByWorkspaceIdOrderByTermAsc(templateWorkspaceId)) {
            if (copies.size() >= room) {
                break;
            }
            if (!physicalNames.contains(term.getTerm()) || existing.contains(term.getTerm())) {
                continue;
            }
            copies.add(new WorkspaceTerm(workspaceId, term.getTerm(), term.getLabel(),
                    term.getTermTypes(), userId));
        }
        if (copies.isEmpty()) {
            return 0;
        }
        termRepository.saveAll(copies);
        return copies.size();
    }

    /** content의 테이블·컬럼 물리명 모음(소문자 정규화) — 파싱 실패 시 빈 집합(사전 복사 없음, 복제는 계속) */
    private Set<String> physicalNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        try {
            for (JsonNode table : objectMapper.readTree(content).path("model").path("tables")) {
                addPhysicalName(names, table);
                for (JsonNode column : table.path("columns")) {
                    addPhysicalName(names, column);
                }
            }
        } catch (RuntimeException ignored) {
            return Set.of();
        }
        return names;
    }

    private static void addPhysicalName(Set<String> names, JsonNode node) {
        String name = node.path("physicalName").asText("");
        if (!name.isBlank()) {
            names.add(name.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** 활성 링크를 문서당 최근 1건으로 모은다 — 공유 갤러리(ShareService.gallery)와 같은 수집 규칙 */
    private Map<Long, ModelShare> latestActiveShareByModel(Set<Long> modelIds) {
        Instant now = Instant.now();
        Map<Long, ModelShare> latestByModel = new LinkedHashMap<>();
        for (ModelShare share : shareRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            if (modelIds.contains(share.getModelId()) && isActive(share, now)) {
                latestByModel.putIfAbsent(share.getModelId(), share); // 최근 발급순이라 선두가 그 문서의 최신 링크
            }
        }
        return latestByModel;
    }

    /** 링크 기간 판정 — 시작일 null은 즉시, 종료일 null은 무제한 (1.10과 같은 규칙) */
    private static boolean isActive(ModelShare share, Instant now) {
        return (share.getStartsAt() == null || !now.isBefore(share.getStartsAt()))
                && (share.getEndsAt() == null || !now.isAfter(share.getEndsAt()));
    }

    /** content에서 테이블·관계 수 — 카드 표기용이므로 파싱 실패 시 0으로 내린다(목록 전체가 실패하지 않는다) */
    private Counts parseCounts(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            return new Counts(root.path("model").path("tables").size(),
                    root.path("model").path("relationships").size());
        } catch (RuntimeException ex) {
            return new Counts(0, 0);
        }
    }

    /** 복제 문서 이름 — 요청 값 우선, 생략하면 원본 이름 (SQL 가져오기 modelName 관례) */
    private static String cloneName(CloneFromTemplateRequest request, Model source) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            name = source.getName();
        }
        if (name.length() > 100) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.doc-name.length");
        }
        return name;
    }

    /** 활성 databaseType 검증 — 비활성화된 종류의 템플릿은 복제 불가 (생성 관례 유지) */
    private String requireActiveDatabaseType(String databaseType) {
        if (databaseTypeRepository.findByCodeAndIsActiveTrue(databaseType).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_DBMS_TYPE);
        }
        return databaseType;
    }

    /** 템플릿 복제 v0 요약 — {created:true, source:template, templateId, tables, relationships} (웹 배지 인식 규칙) */
    private static String templateSummary(Model source, Counts counts) {
        return "{\"created\":true,\"source\":\"template\",\"templateId\":%d,\"tables\":%d,\"relationships\":%d}"
                .formatted(source.getId(), counts.tables(), counts.relationships());
    }

    private Long templateWorkspaceId() {
        AppProperties.Template template = properties.template();
        return template == null ? null : template.workspaceId();
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

    private record Counts(int tables, int relationships) {
    }
}
