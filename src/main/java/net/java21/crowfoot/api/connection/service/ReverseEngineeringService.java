package net.java21.crowfoot.api.connection.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.dto.ReverseEngineeringRequest;
import net.java21.crowfoot.api.connection.dto.ReverseEngineeringResponse;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelDiagram;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.repository.ModelDiagramRepository;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.model.service.ModelVersionPruner;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final ConnectionEndpointResolver endpoints;
    private final DbConnectionRepository connectionRepository;
    private final ModelRepository modelRepository;
    private final ModelDiagramRepository modelDiagramRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final ModelVersionPruner modelVersionPruner;
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
            throw BusinessException.of(ErrorCode.DUPLICATED_NAME, "detail.doc-name.duplicated");
        }
        // 리버스는 FK 인덱스를 문서에 채워 넣는다(assemble 3인자 기본과 같다)
        ReverseContentAssembler.AssembledContent assembled = ConnectionSchemaReader.read(
                connection, endpoints, crypto, introspectors, assembler, true);

        Model model = modelRepository.save(new Model(workspaceId, modelName, request.description(),
                connection.getDbmsType(), assembled.content(), userId));
        // 원천 커넥션 연관 — 이 문서가 어느 연결에서 왔는지 기억해 동기화 버튼 노출 근거가 된다
        model.setSourceConnectionId(connectionId);
        modelDiagramRepository.save(new ModelDiagram(model.getId(), MAIN_DIAGRAM_NAME, EMPTY_LAYOUT, true));
        // v0 스냅샷 — 리버스로 태어난 문서의 요약은 고정형 JSON(08-core/02-model.md 1.11)
        modelVersionRepository.save(new ModelVersion(model.getId(), model.getVersion(),
                assembled.content(), reverseSummary(assembled), null, userId, model.getCreatedAt()));
        modelVersionPruner.prune(model.getId(), model.getVersion(), userId);
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
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.doc-name.length");
        }
        return name;
    }

    private ModelResponse toResponse(Model model) {
        return ModelResponse.of(model, userRepository.findById(model.getCreatedBy()).orElse(null));
    }
}
