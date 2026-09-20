package net.java21.crowfoot.api.connection.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.dto.ReverseEngineeringRequest;
import net.java21.crowfoot.api.connection.dto.ReverseEngineeringResponse;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.JdbcDiagnostics;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.repository.ModelDiagramRepository;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * 리버스 엔지니어링 (08-core/06-connection.md Section 3.6) — 커넥션으로 스키마를 읽어
 * Canonical content v1로 조립하고 신규 문서를 생성·저장까지 한 번에 수행한다.
 * 조립 규칙의 원천은 05-editor/04-dbms-engineering.md Section 3.2.
 *
 * <p>introspection(외부 JDBC 호출, 최대 수 초)도 한 트랜잭션에 둔다 — 저수요 자원이라
 * 커넥션 점유를 감수하고 문서·다이어그램 생성의 원자성을 우선한다. introspect가 실패하면
 * 아직 쓴 것이 없어 롤백 비용도 없다.
 */
@Service
@RequiredArgsConstructor
public class ReverseEngineeringService {

    /** main 다이어그램 초기 레이아웃 — ModelService와 같은 형태 */
    private static final String EMPTY_LAYOUT = "{\"nodes\":[],\"edges\":[],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
    private static final String MAIN_DIAGRAM_NAME = "main";
    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;

    private final DbConnectionRepository connectionRepository;
    private final ModelRepository modelRepository;
    private final ModelDiagramRepository modelDiagramRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ConnectionCrypto crypto;
    private final Introspectors introspectors;
    private final ReverseContentAssembler assembler;

    @Transactional
    public ReverseEngineeringResponse reverse(long userId, long workspaceId, long connectionId,
                                               ReverseEngineeringRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        DbConnection connection = connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));

        String modelName = modelName(connection, request);
        if (modelRepository.existsByWorkspaceIdAndName(workspaceId, modelName)) {
            throw new BusinessException(ErrorCode.DUPLICATED_NAME, "이미 존재하는 문서 이름입니다");
        }
        SchemaIntrospector introspector = introspectors.forDbmsType(connection.getDbmsType());
        if (introspector == null) {
            throw new BusinessException(ErrorCode.INVALID_DBMS_TYPE);
        }

        IntrospectedSchema schema;
        try (Connection jdbc = introspectors.open(introspector, connection.getHost(), connection.getPort(),
                connection.getDatabaseName(), connection.getUsername(), crypto.decrypt(connection.getPassword()))) {
            schema = introspector.introspect(jdbc, connection.getSchemaName());
        } catch (SQLException e) {
            // 원문이 사용자에게 더 실용적인 계열(스키마 없음 등)은 diagnoseStatement가 골라 준다 — 접속·인증은 분류 문구 유지
            throw new BusinessException(ErrorCode.CONNECTION_UNREACHABLE, JdbcDiagnostics.diagnoseStatement(e));
        }

        ReverseContentAssembler.AssembledContent assembled = assembler.assemble(schema, introspector);
        if (assembled.content().getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.REVERSE_FAILED,
                    "스키마가 너무 커 문서 상한(5MB)을 초과했습니다 — 대상 스키마를 줄여 다시 시도하세요");
        }

        Model model = modelRepository.save(new Model(workspaceId, modelName, request.description(),
                connection.getDbmsType(), assembled.content(), userId));
        // 원천 커넥션 연관 — 이 문서가 어느 연결에서 왔는지 기억해 동기화 버튼 노출 근거가 된다
        model.setSourceConnectionId(connectionId);
        modelDiagramRepository.save(new ModelDiagram(model.getId(), MAIN_DIAGRAM_NAME, EMPTY_LAYOUT, true));
        // v0 스냅샷 — 리버스로 태어난 문서의 요약은 고정형 JSON(08-core/02-model.md 1.11)
        modelVersionRepository.save(new ModelVersion(model.getId(), model.getVersion(),
                assembled.content(), reverseSummary(assembled), null, userId, model.getCreatedAt()));
        auditRecorder.record(userId, "CONNECTION_REVERSE_ENGINEERED", "CONNECTION",
                Long.toString(connectionId), Map.of(
                        "modelId", Long.toString(model.getId()),
                        "tables", assembled.tableCount(),
                        "relationships", assembled.relationshipCount()));
        return new ReverseEngineeringResponse(toResponse(model), assembled.tableCount(),
                assembled.relationshipCount(), assembled.skipped());
    }

    /** 리버스 v0 요약 — {created:true, tables:N, relationships:M} (웹이 이 형태를 인식해 렌더) */
    private static String reverseSummary(ReverseContentAssembler.AssembledContent assembled) {
        return "{\"created\":true,\"tables\":%d,\"relationships\":%d}"
                .formatted(assembled.tableCount(), assembled.relationshipCount());
    }

    /** 문서 이름 — 요청 값 우선, 생략하면 "{커넥션 이름} ERD" (06-connection.md 3.6) */
    private static String modelName(DbConnection connection, ReverseEngineeringRequest request) {
        String name = request == null || request.modelName() == null
                ? ""
                : request.modelName().trim();
        if (name.isEmpty()) {
            name = connection.getName() + " ERD";
        }
        if (name.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "문서 이름은 1~100자여야 합니다");
        }
        return name;
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
