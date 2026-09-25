package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.model.ddl.DdlContent;
import net.java21.crowfoot.api.model.ddl.DdlGenerator;
import net.java21.crowfoot.api.model.ddl.Dialects;
import net.java21.crowfoot.api.model.ddl.DbmsTemplates;
import net.java21.crowfoot.api.model.ddl.DbmsTemplates.DbmsTemplate;
import net.java21.crowfoot.api.model.ddl.ErdContentParser;
import net.java21.crowfoot.api.model.ddl.SqlDialect;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.dto.DdlWarningResponse;
import net.java21.crowfoot.api.model.dto.ModelDdlResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DDL 스크립트 생성 (05-editor/04-dbms-engineering.md §3.1 — 08-core/02-model.md Section 1.7).
 *
 * <p>대상 DBMS는 문서 메타 databaseType에서 파생한다(문서 생성 시점 고정).
 * 해석·조립은 {@link net.java21.crowfoot.api.model.ddl} 전략 구조가 담당하고,
 * 이 서비스는 권한·모델 로드·content 파싱만 잇는다.
 */
@Service
@RequiredArgsConstructor
public class DdlService {

    private final ModelRepository modelRepository;
    private final RoleChecker roleChecker;
    private final ObjectMapper objectMapper;

    /** 생성(Viewer 이상 — 읽기 동작) — 마지막 저장 본문 기준 */
    @Transactional(readOnly = true)
    public ModelDdlResponse generate(long userId, long workspaceId, long modelId) {
        roleChecker.requireMember(userId, workspaceId);
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));

        String templateId = DbmsTemplates.templateIdForDatabase(model.getDatabaseType());
        DbmsTemplate template = DbmsTemplates.byId(templateId);
        SqlDialect dialect = Dialects.byId(templateId);
        DdlContent content = parseContent(model.getContent());

        DdlGenerator.Result result = DdlGenerator.generate(content, dialect, template.label(), model.getName());
        return new ModelDdlResponse(
                result.sql(),
                result.warnings().stream().map(w -> new DdlWarningResponse(w.code(), w.message())).toList(),
                content.tables().size(),
                content.relationships().size());
    }

    /** 저장 시점 JSON 구문 검증을 통과한 content만 존재한다 — 여기는 방어 오류 처리 */
    private DdlContent parseContent(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            return ErdContentParser.parse(root);
        } catch (JacksonException e) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.model.parse");
        }
    }
}
