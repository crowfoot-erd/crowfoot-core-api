package net.java21.crowfoot.api.connection.service;

import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.introspect.IntrospectedSchema;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.JdbcDiagnostics;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.reverse.ReverseContentAssembler;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 커넥션 스키마 읽기 절반 — 접속 → introspect → Canonical 조립 → 문서 상한 검사.
 * 리버스(ReverseEngineeringService — 문서 생성)와 스키마 조회(SchemaIntrospectionService — 동기화 원천)가
 * 같은 규칙으로 읽도록 한곳에 둔다. 트랜잭션은 호출부의 것을 그대로 따른다.
 */
final class ConnectionSchemaReader {

    /** 문서 content 상한 — 리버스로 만드는 문서와 비교 원천이 같은 크기 한계를 둔다 */
    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;

    private ConnectionSchemaReader() {
    }

    static ReverseContentAssembler.AssembledContent read(DbConnection connection, ConnectionEndpointResolver endpoints,
                                                         ConnectionCrypto crypto, Introspectors introspectors,
                                                         ReverseContentAssembler assembler, boolean synthesizeFkIndexes) {
        SchemaIntrospector introspector = introspectors.forDbmsType(connection.getDbmsType());
        if (introspector == null) {
            throw new BusinessException(ErrorCode.INVALID_DBMS_TYPE);
        }

        IntrospectedSchema schema;
        try (Connection jdbc = introspectors.open(introspector, endpoints.resolve(connection).host(), endpoints.resolve(connection).port(),
                connection.getDatabaseName(), connection.getUsername(), crypto.decrypt(connection.getPassword()))) {
            schema = introspector.introspect(jdbc, connection.getSchemaName());
        } catch (SQLException e) {
            // 원문이 사용자에게 더 실용적인 계열(스키마 없음 등)은 diagnoseStatement가 골라 준다 — 접속·인증은 분류 문구 유지
            throw new BusinessException(ErrorCode.CONNECTION_UNREACHABLE, JdbcDiagnostics.diagnoseStatement(e));
        }

        ReverseContentAssembler.AssembledContent assembled = assembler.assemble(schema, introspector, connection.getDbmsType(),
                synthesizeFkIndexes);
        if (assembled.content().getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.REVERSE_FAILED,
                    "스키마가 너무 커 문서 상한(5MB)을 초과했습니다 — 대상 스키마를 줄여 다시 시도하세요");
        }
        return assembled;
    }
}
