package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.service.SchemaIntrospectionService;
import net.java21.crowfoot.api.model.ddl.DdlContent;
import net.java21.crowfoot.api.model.ddl.Dialects;
import net.java21.crowfoot.api.model.ddl.DbmsTemplates;
import net.java21.crowfoot.api.model.ddl.ErdContentParser;
import net.java21.crowfoot.api.model.ddl.MigrationDdlGenerator;
import net.java21.crowfoot.api.model.ddl.RenameDetector;
import net.java21.crowfoot.api.model.ddl.SqlDialect;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.dto.DdlWarningResponse;
import net.java21.crowfoot.api.model.dto.MigrationDdlResponse;
import net.java21.crowfoot.api.model.dto.ModelDeployResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 마이그레이션 DDL 생성·실행 (05-editor/04-dbms-engineering.md §3.3 — 08-core/02-model.md §1.7.1·§1.15).
 *
 * <p>두 가지 비교 원천: (a) 버전 A→B — 두 스냅샷 content, Viewer 이상(1.7 DDL 생성과 같은 읽기).
 * (b) 실제 DB→문서 — 스키마 조회(3.7)로 현재 DB를 읽어 문서(content)를 대상으로 비교,
 * Editor 이상(내부 DB 접속이므로 3.7과 같다). (a)는 생성 전용(스냅샷은 비교 대상일 뿐 실행 대상이
 * 없다), (b) 차분은 {@link #executeConnectionMigration}으로 연결된 DB에 그대로 반영할 수 있다 —
 * 클라이언트 SQL을 받지 않고 실행 시점에 diff를 재계산해 그 문장을 실행한다.
 *
 * <p>트랜잭션을 열지 않는다 — (b)는 외부 JDBC 호출을 커넥션 점유 없이 실행(DeployService 관례),
 * (a)도 읽기 2회뿐이라 원자성 요건이 없다.
 */
@Service
@RequiredArgsConstructor
public class MigrationDdlService {

    private final ModelRepository modelRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final DbConnectionRepository connectionRepository;
    private final SchemaIntrospectionService schemaIntrospectionService;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final DdlStatementExecutor statementExecutor;
    private final net.java21.crowfoot.api.connection.service.McpApplyGuard mcpApplyGuard;

    /** (a) 버전 A→B 마이그레이션 DDL — Viewer 이상. 스냅샷은 불변이므로 읽기만 한다 */
    public MigrationDdlResponse generateVersionMigration(long userId, long workspaceId, long modelId,
                                                         long from, long to) {
        roleChecker.requireMember(userId, workspaceId);
        if (from == to) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.migration.two-versions");
        }
        Model model = requireModel(modelId, workspaceId);
        String fromContent = modelVersionRepository.findByModelIdAndVersion(modelId, from)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_VERSION_NOT_FOUND)).getContent();
        String toContent = modelVersionRepository.findByModelIdAndVersion(modelId, to)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_VERSION_NOT_FOUND)).getContent();

        return toResponse(generate(model, parse(fromContent), parse(toContent), "v" + from, "v" + to, false),
                "v" + from, "v" + to);
    }

    /** (b) 실제 DB→문서 마이그레이션 DDL — Editor 이상. from=DB 현재 스키마, to=문서(마지막 저장 본문) */
    public MigrationDdlResponse generateConnectionMigration(long userId, long workspaceId, long modelId,
                                                            long connectionId) {
        roleChecker.requireEditor(userId, workspaceId);
        Model model = requireModel(modelId, workspaceId);
        DbConnection connection = requireConnection(connectionId, workspaceId);
        MigrationDdlGenerator.Result result = connectionDiff(model, connection);
        MigrationDdlResponse response = toResponse(result, "DB", "문서");
        auditRecorder.record(userId, "MODEL_MIGRATION_DDL_GENERATED", "MODEL", Long.toString(modelId),
                Map.of("connectionId", Long.toString(connectionId),
                        "statements", response.statementCount()));
        return response;
    }

    /**
     * (b) 차분 실행(1.15) — Editor 이상. 클라이언트 SQL을 받지 않고 실행 시점에 DB→문서 diff를
     * 재계산해 그 문장을 커넥션 DB에 반영한다(주입 방지·실행 시점 최신 상태). 문장 실패는
     * 결과 항목으로 보고한다(부분 실패 리포트 — 1.8 배포와 같은 규칙).
     */
    public ModelDeployResponse executeConnectionMigration(long userId, long workspaceId, long modelId,
                                                          long connectionId, boolean includeDestructive) {
        roleChecker.requireEditor(userId, workspaceId);
        Model model = requireModel(modelId, workspaceId);
        DbConnection connection = requireConnection(connectionId, workspaceId);
        // 토큰으로 온 요청(MCP)은 허용된 커넥션에만 실행한다 (08-core/06-connection.md Section 2.1)
        mcpApplyGuard.requireAllowed(connection);
        MigrationDdlGenerator.Result result = connectionDiff(model, connection);

        // 이미 동일(0문장)이면 접속 없이 빈 리포트 — introspection이 도달성을 증명했다(2차 접속 불필요)
        // 삭제 문장(테이블·컬럼·제약·인덱스 삭제)은 요청이 명시했을 때만 실행한다 — 기본은 추가와 변경만이다
        List<String> toRun = includeDestructive ? result.statements() : result.safeStatements();
        int skipped = includeDestructive ? 0 : result.destructive().size();
        List<ModelDeployResponse.Statement> statements = toRun.isEmpty()
                ? List.of()
                : statementExecutor.execute(connection, toRun);

        int failed = (int) statements.stream().filter(statement -> !statement.ok()).count();
        auditRecorder.record(userId, "MODEL_MIGRATION_EXECUTED", "MODEL", Long.toString(modelId), Map.of(
                "connectionId", Long.toString(connectionId),
                "connectionName", connection.getName(),
                "executed", statements.size() - failed,
                "failed", failed,
                "includeDestructive", includeDestructive,
                "skippedDestructive", skipped));
        return new ModelDeployResponse(statements.size() - failed, failed, statements,
                result.warnings().stream().map(w -> new DdlWarningResponse(w.code(), w.message())).toList(), skipped);
    }

    /** (b) 공용 diff — 커넥션 현재 스키마(introspection)를 문서와 비교한다. 생성·실행이 같은 원천을 쓴다 */
    private MigrationDdlGenerator.Result connectionDiff(Model model, DbConnection connection) {
        // 문서 방언과 커넥션 DBMS가 다르면 DDL이 그 데이터베이스에 맞지 않는다 (1.8 배포와 같은 검사)
        if (!model.getDatabaseType().trim().equalsIgnoreCase(connection.getDbmsType().trim())) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.migration.dbms-mismatch",
                    model.getDatabaseType(), connection.getDbmsType());
        }
        // v1.34부터 리버스가 인덱스를 읽는다 — 인덱스도 비교한다. 비교용 조립은 DB에 있는 인덱스만 담는다
        String dbContent = schemaIntrospectionService.introspectContentForComparison(connection);
        DdlContent document = parse(model.getContent());
        // 이름 변경 — 버전 기록에서 같은 테이블·컬럼 id의 예전 이름을 찾아 DB의 예전 이름을 지금 이름으로 맞춘다(RENAME)
        RenameDetector.Result renames = RenameDetector.detect(parse(dbContent), document, previousContents(model));
        return generate(model, renames.adjustedFrom(), document, "DB", "문서", false, renames.renames());
    }

    /** 공용 조립 — 방언은 문서 메타 databaseType에서 파생한다(1.7과 같다) */
    private MigrationDdlGenerator.Result generate(Model model, DdlContent from, DdlContent to,
                                                  String fromLabel, String toLabel, boolean skipIndexes) {
        return generate(model, from, to, fromLabel, toLabel, skipIndexes, List.of());
    }

    private MigrationDdlGenerator.Result generate(Model model, DdlContent from, DdlContent to,
                                                  String fromLabel, String toLabel, boolean skipIndexes,
                                                  List<RenameDetector.Rename> renames) {
        String templateId = DbmsTemplates.templateIdForDatabase(model.getDatabaseType());
        SqlDialect dialect = Dialects.byId(templateId);
        return MigrationDdlGenerator.generate(from, to, dialect,
                DbmsTemplates.byId(templateId).label(), model.getName(), fromLabel, toLabel, skipIndexes, renames);
    }

    /** 이름 변경을 찾을 예전 본체 — 최근 버전부터(보존 정책상 최근 기록만 남는다) */
    private List<DdlContent> previousContents(Model model) {
        List<DdlContent> contents = new java.util.ArrayList<>();
        for (net.java21.crowfoot.api.model.domain.ModelVersion version
                : modelVersionRepository.findTop50ByModelIdOrderByVersionDesc(model.getId())) {
            try {
                contents.add(ErdContentParser.parse(objectMapper.readTree(version.getContent())));
            } catch (JacksonException e) {
                // 읽지 못한 버전은 건너뛴다 — 이름 변경 감지는 보조 정보다
            }
        }
        return contents;
    }

    private MigrationDdlResponse toResponse(MigrationDdlGenerator.Result result,
                                            String fromLabel, String toLabel) {
        return new MigrationDdlResponse(result.sql(),
                result.warnings().stream().map(w -> new DdlWarningResponse(w.code(), w.message())).toList(),
                result.statementCount(), fromLabel, toLabel, result.destructive());
    }

    private DbConnection requireConnection(long connectionId, long workspaceId) {
        return connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
    }

    private Model requireModel(long modelId, long workspaceId) {
        return modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
    }

    /** 저장 시점 JSON 구문 검증을 통과한 content만 존재한다 — 여기는 방어 오류 처리 */
    private DdlContent parse(String content) {
        try {
            return ErdContentParser.parse(objectMapper.readTree(content));
        } catch (JacksonException e) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.model.parse");
        }
    }
}
