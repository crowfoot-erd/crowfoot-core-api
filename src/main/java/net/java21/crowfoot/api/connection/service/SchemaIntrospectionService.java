package net.java21.crowfoot.api.connection.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.dto.ConnectionSchemaResponse;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.JdbcDiagnostics;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * 스키마 조회 (08-core/06-connection.md Section 3.7) — 문서 동기화의 원천 데이터.
 * {@link ReverseEngineeringService}의 읽기 절반(접속 → introspect → 조립)만 문서 생성 없이
 * 수행해 조립된 content를 반환한다. 어댑터 확장 구조(Introspectors 레지스트리)와 조립 규칙
 * (ReverseContentAssembler — 05-editor/04-dbms-engineering.md Section 3.2)을 그대로 재사용한다.
 *
 * <p>읽기 전용이라 트랜잭션을 열지 않는다(외부 JDBC 호출을 커넥션 점유 없이 실행 — DeployService 관례).
 * 받은 content를 문서에 어떻게 반영할지(부분 동기화·보존 규칙)는 전적으로 에디터가 정한다.
 */
@Service
@RequiredArgsConstructor
public class SchemaIntrospectionService {

    /** 리버스(3.6)와 같은 상한 — 비교 원천도 문서와 같은 크기 한계를 둔다 */
    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;

    private final DbConnectionRepository connectionRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ConnectionCrypto crypto;
    private final Introspectors introspectors;
    private final ReverseContentAssembler assembler;

    public ConnectionSchemaResponse introspect(long userId, long workspaceId, long connectionId) {
        roleChecker.requireEditor(userId, workspaceId);
        DbConnection connection = connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));

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

        auditRecorder.record(userId, "CONNECTION_SCHEMA_INTROSPECTED", "CONNECTION",
                Long.toString(connectionId), Map.of(
                        "tables", assembled.tableCount(),
                        "relationships", assembled.relationshipCount()));
        return new ConnectionSchemaResponse(assembled.content(), assembled.tableCount(),
                assembled.relationshipCount(), assembled.skipped());
    }
}
