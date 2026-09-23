package net.java21.crowfoot.api.model.sqlimport;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.model.repository.ModelDiagramRepository;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.model.service.ModelVersionPruner;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL Import (08-core/02-model.md Section 1.12, 05-editor/04-dbms-engineering.md SQL Import v1) —
 * DDL 텍스트를 {@link DdlTextParser}로 중립 스키마로 읽고 리버스 조립기({@code ReverseContentAssembler})로
 * Canonical content v1을 만들어 신규 문서를 생성·저장까지 한 번에 수행한다.
 *
 * <p>저장 시퀀스·관례는 {@code ReverseEngineeringService}를 승계한다(EMPTY_LAYOUT main 다이어그램,
 * v0 스냅샷, 버전 pruner, 감사). 차이는 두 가지뿐 — 원천 커넥션이 없어 {@code sourceConnectionId}를
 * 남기지 않는 것(→ 웹의 DB 동기화 버튼이 자연히 노출되지 않음)과 v0 요약에 {@code "source":"sql"}을
 * 심어 웹이 "SQL 가져오기로 생성" 배지를 렌더하는 것.
 *
 * <p>미리보기(preview)는 저장 없이 파싱·조립까지만 — 생성과 같은 경로를 돌려 개수가 미리보기와
 * 생성 결과에서 정확히 일치하게 한다. 트랜잭션도 필요 없다(외부 I/O·쓰기가 없다).
 */
@Service
@RequiredArgsConstructor
public class SqlImportService {

    private static final String EMPTY_LAYOUT =
            "{\"nodes\":[],\"edges\":[],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
    private static final String MAIN_DIAGRAM_NAME = "main";
    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;
    private static final String DEFAULT_MODEL_NAME = "SQL ERD";

    private final ModelRepository modelRepository;
    private final ModelDiagramRepository modelDiagramRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final ModelVersionPruner modelVersionPruner;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final DatabaseTypeRepository databaseTypeRepository;
    private final Introspectors introspectors;
    private final ReverseContentAssembler assembler;

    /** 생성(Editor 이상) — DDL에서 문서를 만든다. 이름 중복 409, CREATE TABLE 0개면 400 */
    @Transactional
    public SqlImportResponse importDocument(long userId, long workspaceId, SqlImportRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        String databaseType = requireActiveDatabaseType(request.databaseType());
        String modelName = modelName(request);
        if (modelRepository.existsByWorkspaceIdAndName(workspaceId, modelName)) {
            throw new BusinessException(ErrorCode.DUPLICATED_NAME, "이미 존재하는 문서 이름입니다");
        }
        ReverseContentAssembler.AssembledContent assembled = assemble(request.ddl(), databaseType);

        Model model = modelRepository.save(new Model(workspaceId, modelName, request.description(),
                databaseType, assembled.content(), userId));
        // sourceConnectionId는 남기지 않는다 — 커넥션 없이 만든 문서, DB 동기화 대상이 아니다
        modelDiagramRepository.save(new ModelDiagram(model.getId(), MAIN_DIAGRAM_NAME, EMPTY_LAYOUT, true));
        // v0 스냅샷 — SQL 가져오기로 태어난 문서의 요약은 고정형 JSON(08-core/02-model.md 1.12)
        modelVersionRepository.save(new ModelVersion(model.getId(), model.getVersion(),
                assembled.content(), sqlSummary(assembled), null, userId, model.getCreatedAt()));
        modelVersionPruner.prune(model.getId(), model.getVersion(), userId);
        auditRecorder.record(userId, "MODEL_SQL_IMPORTED", "MODEL",
                Long.toString(model.getId()), Map.of(
                        "tables", assembled.tableCount(),
                        "relationships", assembled.relationshipCount()));
        return new SqlImportResponse(toResponse(model), assembled.tableCount(),
                assembled.relationshipCount(), assembled.skipped());
    }

    /** 미리보기(Editor 이상) — 파싱·조립까지만, 저장 없음 */
    public SqlImportPreviewResponse preview(long userId, long workspaceId, SqlImportPreviewRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        String databaseType = requireActiveDatabaseType(request.databaseType());
        DdlTextParser.DdlParseResult parsed = new DdlTextParser().parse(request.ddl());
        requireTables(parsed);
        ReverseContentAssembler.AssembledContent assembled = assembleParsed(parsed, databaseType);

        List<SqlImportPreviewResponse.PreviewTable> tables = new ArrayList<>();
        Set<String> names = parsed.schema().tables().stream()
                .map(IntrospectedSchema.IntrospectedTable::name)
                .collect(Collectors.toSet());
        // 조립기와 같은 유효 조건(양쪽 테이블이 모두 스키마에 있는 FK)으로 세면 미리보기 개수와 생성 결과가 일치한다
        Map<String, Long> fkCountByChild = parsed.schema().foreignKeys().stream()
                .filter(fk -> names.contains(fk.childTable()) && names.contains(fk.parentTable()))
                .collect(Collectors.groupingBy(IntrospectedSchema.IntrospectedFk::childTable,
                        Collectors.counting()));
        for (IntrospectedSchema.IntrospectedTable table : parsed.schema().tables()) {
            tables.add(new SqlImportPreviewResponse.PreviewTable(
                    table.name(), table.comment(), table.columns().size(),
                    table.primaryKeyColumns(),
                    fkCountByChild.getOrDefault(table.name(), 0L).intValue()));
        }
        return new SqlImportPreviewResponse(databaseType, assembled.tableCount(),
                assembled.relationshipCount(), tables, assembled.skipped());
    }

    /* ---------- 파싱·조립 ---------- */

    private ReverseContentAssembler.AssembledContent assemble(String ddl, String databaseType) {
        DdlTextParser.DdlParseResult parsed = new DdlTextParser().parse(ddl);
        requireTables(parsed);
        return assembleParsed(parsed, databaseType);
    }

    private ReverseContentAssembler.AssembledContent assembleParsed(DdlTextParser.DdlParseResult parsed,
                                                                    String databaseType) {
        SchemaIntrospector introspector = introspectors.forDbmsType(databaseType);
        if (introspector == null) {
            throw new BusinessException(ErrorCode.INVALID_DBMS_TYPE);
        }
        ReverseContentAssembler.AssembledContent assembled = assembler.assemble(parsed.schema(), introspector);
        if (assembled.content().getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.REVERSE_FAILED,
                    "DDL이 너무 커 문서 상한(5MB)을 초과했습니다 — 대상 테이블을 줄여 다시 시도하세요");
        }
        // 파서가 건너뛴 문장(CREATE INDEX·VIEW…)도 skipped에 묶는다 — 읽지 못한 입력을 그대로 보여준다
        if (parsed.skipped().isEmpty()) {
            return assembled;
        }
        List<String> skipped = new ArrayList<>(parsed.skipped());
        skipped.addAll(assembled.skipped());
        return new ReverseContentAssembler.AssembledContent(assembled.content(), assembled.tableCount(),
                assembled.relationshipCount(), skipped);
    }

    private static void requireTables(DdlTextParser.DdlParseResult parsed) {
        if (parsed.schema().tables().isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_IMPORT_NO_TABLES);
        }
    }

    /** SQL Import v0 요약 — {created:true, source:sql, tables:N, relationships:M} (웹이 이 형태를 인식해 렌더) */
    private static String sqlSummary(ReverseContentAssembler.AssembledContent assembled) {
        return "{\"created\":true,\"source\":\"sql\",\"tables\":%d,\"relationships\":%d}"
                .formatted(assembled.tableCount(), assembled.relationshipCount());
    }

    /** 문서 이름 — 요청 값 우선, 생략하면 "SQL ERD" (리버스의 "{커넥션 이름} ERD"에 대응) */
    private static String modelName(SqlImportRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            name = DEFAULT_MODEL_NAME;
        }
        if (name.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "문서 이름은 1~100자여야 합니다");
        }
        return name;
    }

    /** 활성 databaseType 검증 — 문서의 DB 종류이자 파싱 타입 정규화 기준 (ModelService.create 관례) */
    private String requireActiveDatabaseType(String databaseType) {
        if (databaseTypeRepository.findByCodeAndIsActiveTrue(databaseType).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 데이터베이스 종류입니다");
        }
        return databaseType;
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
