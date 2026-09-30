package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.model.ddl.DdlContent;
import net.java21.crowfoot.api.model.ddl.DdlGenerator;
import net.java21.crowfoot.api.model.ddl.Dialects;
import net.java21.crowfoot.api.model.ddl.DbmsTemplates;
import net.java21.crowfoot.api.model.ddl.ErdContentParser;
import net.java21.crowfoot.api.model.ddl.SqlDialect;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.dto.DdlWarningResponse;
import net.java21.crowfoot.api.model.dto.ModelDeployResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 포워드 엔지니어링 배포 (05-editor/04-dbms-engineering.md §3.1 — 08-core/02-model.md Section 1.8).
 *
 * <p>문서 DDL을 커넥션 데이터베이스에 문장별로 실행한다. 한 문장이 실패해도 나머지를
 * 계속 실행해 전체 결과를 보고한다(부분 실패 리포트) — 문장 순서는 생성기 순서
 * (CREATE 먼저, FK는 별도 ALTER라 의존 순서 무관)를 그대로 따른다. 실행은 JDBC
 * 자동 커밋이라 스프링 트랜잭션을 물지 않는다. 문장 실행 골격은 {@link DdlStatementExecutor}를
 * 마이그레이션 실행(§1.15)과 공유한다.
 */
@Service
@RequiredArgsConstructor
public class DeployService {

    private final ModelRepository modelRepository;
    private final DbConnectionRepository connectionRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final DdlStatementExecutor statementExecutor;

    /** 배포(Editor 이상) — DDL 생성(1.7) 재사용, 결과는 문장별 성공/실패 */
    public ModelDeployResponse deploy(long userId, long workspaceId, long modelId, long connectionId) {
        roleChecker.requireEditor(userId, workspaceId);
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
        DbConnection connection = connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));

        // 문서 방언과 커넥션 DBMS가 다르면 DDL이 그 데이터베이스에 맞지 않는다
        if (!model.getDatabaseType().trim().equalsIgnoreCase(connection.getDbmsType().trim())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "문서의 DBMS(" + model.getDatabaseType() + ")와 커넥션의 DBMS("
                            + connection.getDbmsType() + ")가 다릅니다");
        }

        DdlGenerator.Result result = generate(model);
        List<ModelDeployResponse.Statement> statements = statementExecutor.execute(connection, result.statements());

        int failed = (int) statements.stream().filter(statement -> !statement.ok()).count();
        auditRecorder.record(userId, "MODEL_DEPLOYED", "MODEL", Long.toString(modelId), Map.of(
                "connectionId", Long.toString(connectionId),
                "connectionName", connection.getName(),
                "executed", statements.size() - failed,
                "failed", failed));
        return new ModelDeployResponse(
                statements.size() - failed,
                failed,
                statements,
                result.warnings().stream().map(w -> new DdlWarningResponse(w.code(), w.message())).toList());
    }

    /** 저장 시점 JSON 구문 검증을 통과한 content만 존재한다 — 여기는 방어 오류 처리 */
    private DdlGenerator.Result generate(Model model) {
        String templateId = DbmsTemplates.templateIdForDatabase(model.getDatabaseType());
        SqlDialect dialect = Dialects.byId(templateId);
        DdlContent content;
        try {
            content = ErdContentParser.parse(objectMapper.readTree(model.getContent()));
        } catch (JacksonException e) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.model.parse");
        }
        return DdlGenerator.generate(content, dialect, DbmsTemplates.byId(templateId).label(), model.getName());
    }
}
