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
import net.java21.crowfoot.api.model.ddl.SqlDialect;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.dto.DdlWarningResponse;
import net.java21.crowfoot.api.model.dto.MigrationDdlResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 마이그레이션 DDL 생성 (05-editor/04-dbms-engineering.md §3.3 — 08-core/02-model.md Section 1.7.1).
 *
 * <p>두 가지 비교 원천: (a) 버전 A→B — 두 스냅샷 content, Viewer 이상(1.7 DDL 생성과 같은 읽기).
 * (b) 실제 DB→문서 — 스키마 조회(3.7)로 현재 DB를 읽어 문서(content)를 대상으로 비교,
 * Editor 이상(내부 DB 접속이므로 3.7과 같다). 결과는 어디까지나 검토·복사용 스크립트다 —
 * 실행은 제공하지 않는다.
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

        return generate(model, parse(fromContent), parse(toContent), "v" + from, "v" + to, false);
    }

    /** (b) 실제 DB→문서 마이그레이션 DDL — Editor 이상. from=DB 현재 스키마, to=문서(마지막 저장 본문) */
    public MigrationDdlResponse generateConnectionMigration(long userId, long workspaceId, long modelId,
                                                            long connectionId) {
        roleChecker.requireEditor(userId, workspaceId);
        Model model = requireModel(modelId, workspaceId);
        DbConnection connection = connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));

        // 문서 방언과 커넥션 DBMS가 다르면 DDL이 그 데이터베이스에 맞지 않는다 (1.8 배포와 같은 검사)
        if (!model.getDatabaseType().trim().equalsIgnoreCase(connection.getDbmsType().trim())) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.migration.dbms-mismatch",
                    model.getDatabaseType(), connection.getDbmsType());
        }

        String dbContent = schemaIntrospectionService.introspectContent(connection);
        MigrationDdlResponse response = generate(model, parse(dbContent), parse(model.getContent()),
                "DB", "문서", true);
        auditRecorder.record(userId, "MODEL_MIGRATION_DDL_GENERATED", "MODEL", Long.toString(modelId),
                Map.of("connectionId", Long.toString(connectionId),
                        "statements", response.statementCount()));
        return response;
    }

    /** 공용 조립 — 방언은 문서 메타 databaseType에서 파생한다(1.7과 같다) */
    private MigrationDdlResponse generate(Model model, DdlContent from, DdlContent to,
                                          String fromLabel, String toLabel, boolean skipIndexes) {
        String templateId = DbmsTemplates.templateIdForDatabase(model.getDatabaseType());
        SqlDialect dialect = Dialects.byId(templateId);
        MigrationDdlGenerator.Result result = MigrationDdlGenerator.generate(from, to, dialect,
                DbmsTemplates.byId(templateId).label(), model.getName(), fromLabel, toLabel, skipIndexes);
        return new MigrationDdlResponse(result.sql(),
                result.warnings().stream().map(w -> new DdlWarningResponse(w.code(), w.message())).toList(),
                result.statementCount(), fromLabel, toLabel);
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
