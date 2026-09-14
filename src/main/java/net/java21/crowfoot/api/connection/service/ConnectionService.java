package net.java21.crowfoot.api.connection.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.dto.ConnectionResponse;
import net.java21.crowfoot.api.connection.dto.ConnectionTestResponse;
import net.java21.crowfoot.api.connection.dto.CreateConnectionRequest;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.JdbcDiagnostics;
import net.java21.crowfoot.api.connection.introspect.SchemaIntrospector;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 커넥션 관리 API (08-core/06-connection.md Section 3) — 목록·등록·변경·삭제·접속 테스트.
 * 리버스 엔지니어링은 {@link ReverseEngineeringService}.
 *
 * <p>권한: 목록은 멤버 전체(응답에 비밀번호 미포함), 그 외는 Editor 이상 — 자격 증명을
 * 다루는 자원이라 설계 변경 위계에 둔다. 비밀번호는 평문 요청 → 즉시 AES-256-GCM 암호화.
 */
@Service
@RequiredArgsConstructor
public class ConnectionService {

    private final DbConnectionRepository connectionRepository;
    private final DatabaseTypeRepository databaseTypeRepository;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ConnectionCrypto crypto;
    private final Introspectors introspectors;
    private final ManagedDatabaseRepository managedDatabaseRepository;

    /** 목록(멤버 전체 — 3.1) */
    @Transactional(readOnly = true)
    public java.util.List<ConnectionResponse> list(long userId, long workspaceId) {
        roleChecker.requireMember(userId, workspaceId);
        return connectionRepository.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** 등록(Editor 이상 — 3.2) — 등록 시 접속을 검증하지 않는다(테스트와 분리) */
    @Transactional
    public ConnectionResponse create(long userId, long workspaceId, CreateConnectionRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        requireActiveDbmsType(request.dbmsType());
        String schemaName = normalizeSchemaName(request.schemaName(), request.dbmsType());
        DbConnection saved = connectionRepository.save(new DbConnection(
                workspaceId, request.name().trim(), request.dbmsType().trim(), request.host().trim(),
                request.port(), request.databaseName().trim(), schemaName, request.username().trim(),
                crypto.encrypt(request.password()), userId));
        auditRecorder.record(userId, "CONNECTION_CREATED", "CONNECTION",
                Long.toString(saved.getId()), Map.of(
                        "name", saved.getName(),
                        "dbmsType", saved.getDbmsType()));
        return toResponse(saved);
    }

    /** 변경(Editor 이상 — 3.3) — PATCH 의미론: 생략은 변경 없음, password는 왔을 때만 재암호화 */
    @Transactional
    public ConnectionResponse patch(long userId, long workspaceId, long connectionId, JsonNode body) {
        roleChecker.requireEditor(userId, workspaceId);
        DbConnection connection = connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
        // 매니지드 발급 커넥션은 이름만 바꿀 수 있다 — 접속 정보를 고치면 발급 스키마와 정합이 깨진다
        if (managedDatabaseRepository.findByConnectionId(connectionId).isPresent()) {
            boolean touchesCredential = body.has("dbmsType") || body.has("host") || body.has("port")
                    || body.has("databaseName") || body.has("schemaName")
                    || body.has("username") || body.has("password");
            if (touchesCredential) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "매니지드 발급 커넥션은 이름만 변경할 수 있습니다 — 정리는 철회로 해야 합니다");
            }
        }

        if (body.has("name") && !body.get("name").isNull()) {
            String name = body.get("name").asText().trim();
            if (name.isEmpty() || name.length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이름은 1~100자여야 합니다");
            }
            connection.setName(name);
        }
        if (body.has("dbmsType") && !body.get("dbmsType").isNull()) {
            String dbmsType = body.get("dbmsType").asText().trim();
            requireActiveDbmsType(dbmsType);
            connection.setDbmsType(dbmsType);
            // PostgreSQL → 다른 DBMS 전환 시 스키마 지정은 의미를 잃는다 — 함께 정리
            if (!SUPPORTED_SCHEMA_DBMS.equals(dbmsType)) {
                connection.setSchemaName(null);
            }
        }
        if (body.has("host") && !body.get("host").isNull()) {
            String host = body.get("host").asText().trim();
            if (host.isEmpty() || host.length() > 255) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "호스트는 1~255자여야 합니다");
            }
            connection.setHost(host);
        }
        if (body.has("port") && !body.get("port").isNull() && body.get("port").isNumber()) {
            int port = body.get("port").asInt();
            if (port < 1 || port > 65535) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "포트는 1~65535여야 합니다");
            }
            connection.setPort(port);
        }
        if (body.has("databaseName") && !body.get("databaseName").isNull()) {
            String databaseName = body.get("databaseName").asText().trim();
            if (databaseName.isEmpty() || databaseName.length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "데이터베이스 이름은 1~100자여야 합니다");
            }
            connection.setDatabaseName(databaseName);
        }
        // schemaName: null·빈 문자열은 지정 해제, 값이 오면 PostgreSQL 커넥션에만 허용한다
        if (body.has("schemaName")) {
            connection.setSchemaName(body.get("schemaName").isNull()
                    ? null
                    : normalizeSchemaName(body.get("schemaName").asText(), connection.getDbmsType()));
        }
        if (body.has("username") && !body.get("username").isNull()) {
            String username = body.get("username").asText().trim();
            if (username.isEmpty() || username.length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "사용자 이름은 1~100자여야 합니다");
            }
            connection.setUsername(username);
        }
        if (body.has("password") && !body.get("password").isNull()) {
            String password = body.get("password").asText();
            if (password.isEmpty() || password.length() > 255) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호는 1~255자여야 합니다");
            }
            connection.setPassword(crypto.encrypt(password));
        }

        auditRecorder.record(userId, "CONNECTION_UPDATED", "CONNECTION",
                Long.toString(connection.getId()), null);
        return toResponse(connection);
    }

    /** 삭제(Editor 이상 — 3.4) — 물리 삭제. 이 커넥션으로 만든 문서는 남는다 */
    @Transactional
    public void delete(long userId, long workspaceId, long connectionId) {
        roleChecker.requireEditor(userId, workspaceId);
        DbConnection connection = connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
        // 매니지드 발급 커넥션은 철회가 통째로 정리한다 — 커넥션만 지우면 스키마가 방치된다
        if (managedDatabaseRepository.findByConnectionId(connectionId).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "매니지드 발급 커넥션은 삭제할 수 없습니다 — 발급 철회로 정리해야 합니다");
        }
        connectionRepository.deleteById(connection.getId());
        auditRecorder.record(userId, "CONNECTION_DELETED", "CONNECTION",
                Long.toString(connection.getId()), Map.of("name", connection.getName()));
    }

    /**
     * 접속 테스트(Editor 이상 — 3.5) — SELECT 1로 지연을 재고 결과를 보고한다.
     * 접속 실패도 계약 응답(200 + connected:false + 분류 문구)이라 트랜잭션을 물지 않는다.
     */
    public ConnectionTestResponse test(long userId, long workspaceId, long connectionId) {
        roleChecker.requireEditor(userId, workspaceId);
        DbConnection connection = connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
        SchemaIntrospector introspector = introspectors.forDbmsType(connection.getDbmsType());
        if (introspector == null) {
            throw new BusinessException(ErrorCode.INVALID_DBMS_TYPE);
        }
        long start = System.nanoTime();
        try (Connection jdbc = introspectors.open(introspector, connection.getHost(), connection.getPort(),
                connection.getDatabaseName(), connection.getUsername(), crypto.decrypt(connection.getPassword()));
             Statement statement = jdbc.createStatement()) {
            // 스키마 지정 커넥션은 존재 검증까지 마쳐야 진짜 연결 가능 — 오타를 테스트 단계에서 잡는다
            introspector.applySessionSchema(jdbc, connection.getSchemaName());
            statement.execute("SELECT 1");
            return ConnectionTestResponse.ok((System.nanoTime() - start) / 1_000_000);
        } catch (SQLException e) {
            return ConnectionTestResponse.fail(JdbcDiagnostics.diagnoseStatement(e));
        }
    }

    private void requireActiveDbmsType(String dbmsType) {
        String code = dbmsType == null ? "" : dbmsType.trim();
        if (databaseTypeRepository.findByCodeAndIsActiveTrue(code).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_DBMS_TYPE);
        }
    }

    /** 스키마 지정을 받아들이는 DBMS — database = schema인 MySQL 등은 대상이 곧 접속 DB다 */
    private static final String SUPPORTED_SCHEMA_DBMS = "postgresql";

    /** 스키마명 식별자 규칙(영문·숫자·밑줄, 첫 글자 숫자 금지) — set_config 바인딩에 앞서 문법을 잡는다 */
    private static final java.util.regex.Pattern SCHEMA_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,99}$");

    /** 빈 칸은 미지정(null)으로 정규화 — PostgreSQL이 아니면 거부, 패턴도 검사한다 */
    private String normalizeSchemaName(String schemaName, String dbmsType) {
        String normalized = schemaName == null ? "" : schemaName.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (!SUPPORTED_SCHEMA_DBMS.equals(dbmsType == null ? "" : dbmsType.trim())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "스키마는 PostgreSQL 커넥션에서만 지정할 수 있습니다");
        }
        if (!SCHEMA_NAME_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "스키마 이름은 영문·숫자·밑줄로 100자 이내여야 합니다");
        }
        return normalized;
    }

    private ConnectionResponse toResponse(DbConnection connection) {
        User creator = userRepository.findById(connection.getCreatedBy()).orElse(null);
        UserRefResponse createdBy = creator == null
                ? null
                : new UserRefResponse(Long.toString(creator.getId()), creator.getName());
        return new ConnectionResponse(
                Long.toString(connection.getId()),
                Long.toString(connection.getWorkspaceId()),
                connection.getName(),
                connection.getDbmsType(),
                connection.getHost(),
                connection.getPort(),
                connection.getDatabaseName(),
                connection.getSchemaName(),
                connection.getUsername(),
                createdBy,
                connection.getCreatedAt());
    }
}
