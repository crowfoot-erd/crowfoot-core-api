package net.java21.crowfoot.api.connection.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.dto.ConnectionSchemaResponse;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;

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

    private final ConnectionEndpointResolver endpoints;
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

        ReverseContentAssembler.AssembledContent assembled = doIntrospect(connection, true);

        auditRecorder.record(userId, "CONNECTION_SCHEMA_INTROSPECTED", "CONNECTION",
                Long.toString(connectionId), Map.of(
                        "tables", assembled.tableCount(),
                        "relationships", assembled.relationshipCount()));
        return new ConnectionSchemaResponse(assembled.content(), assembled.tableCount(),
                assembled.relationshipCount(), assembled.skipped());
    }

    /** 조립된 content만 반환(역할·감사 없음) — 문서↔DB 마이그레이션 DDL(02-model.md 1.7.1)이 재사용 */
    public String introspectContent(DbConnection connection) {
        return doIntrospect(connection, true).content();
    }

    /** 실제 DB와 문서를 비교할 때 쓰는 조립 — DB에 있는 것만 담는다(FK 인덱스를 만들어 넣지 않는다) */
    public String introspectContentForComparison(DbConnection connection) {
        return doIntrospect(connection, false).content();
    }

    /** 접속 → introspect → Canonical 조립 본문 — 읽기 절반(리버스 3.6과 같은 규칙) */
    private ReverseContentAssembler.AssembledContent doIntrospect(DbConnection connection, boolean synthesizeFkIndexes) {
        return ConnectionSchemaReader.read(connection, endpoints, crypto, introspectors, assembler, synthesizeFkIndexes);
    }
}
