package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.service.SchemaIntrospectionService;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.dto.MigrationDdlResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 마이그레이션 DDL 서비스 단위 테스트 (08-core/02-model.md Section 1.7.1) —
 * (a) 버전 비교(Viewer·감사 없음)와 (b) 문서↔DB 비교(Editor·DBMS 일치 검사·감사)를 검증한다.
 * 스키마 조회는 스텁으로 대체해 서비스 간 결계만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class MigrationDdlServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** v2(=이행 원천) — users(id, email). DB 조회 결과(x1·x2 id)도 물리명 매칭 검증을 위해 같은 구조 */
    private static final String V2 = """
            {"schemaVersion":1,"model":{"tables":[
              {"id":"t1","physicalName":"users","columns":[
                {"id":"c1","physicalName":"id","dataType":"BIGINT","nullable":false,"autoIncrement":true},
                {"id":"c2","physicalName":"email","dataType":"VARCHAR","length":255,"nullable":false}]}],
              "relationships":[]}}
            """;

    /** DB에서 읽은 현재 스키마 — 컬럼 id가 전부 다르게 발급된다(리버스 UUID) */
    private static final String DB = """
            {"schemaVersion":1,"model":{"tables":[
              {"id":"x-t1","physicalName":"users","columns":[
                {"id":"x1","physicalName":"id","dataType":"BIGINT","nullable":false,"autoIncrement":true},
                {"id":"x2","physicalName":"email","dataType":"VARCHAR","length":255,"nullable":false}]}],
              "relationships":[]}}
            """;

    /** v3(=이행 대상) — grade 컬럼 추가 */
    private static final String V3 = """
            {"schemaVersion":1,"model":{"tables":[
              {"id":"t1","physicalName":"users","columns":[
                {"id":"c1","physicalName":"id","dataType":"BIGINT","nullable":false,"autoIncrement":true},
                {"id":"c2","physicalName":"email","dataType":"VARCHAR","length":255,"nullable":false},
                {"id":"c3","physicalName":"grade","dataType":"VARCHAR","length":10,"nullable":true}]}],
              "relationships":[]}}
            """;

    private static final String DEV_KEY = java.util.Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Mock
    private ModelRepository modelRepository;
    @Mock
    private ModelVersionRepository modelVersionRepository;
    @Mock
    private DbConnectionRepository connectionRepository;
    @Mock
    private SchemaIntrospectionService schemaIntrospectionService;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;

    private MigrationDdlService service;

    @BeforeEach
    void setUp() {
        service = new MigrationDdlService(modelRepository, modelVersionRepository, connectionRepository,
                schemaIntrospectionService, roleChecker, auditRecorder, MAPPER);
    }

    private Model model(String databaseType, String content) {
        Model model = new Model(77L, "회원 ERD", null, databaseType, content, 7L);
        ReflectionTestUtils.setField(model, "id", 501L);
        return model;
    }

    private DbConnection connection(String dbmsType) {
        DbConnection connection = new DbConnection(77L, "개발 DB", dbmsType, "db.dev", 3306,
                "crowfoot", null, "crowfoot", new ConnectionCrypto(DEV_KEY).encrypt("pw"), 7L);
        ReflectionTestUtils.setField(connection, "id", 11L);
        return connection;
    }

    private ModelVersion snapshot(long version, String content) {
        return new ModelVersion(501L, version, content, null, null, 7L,
                Instant.parse("2026-09-20T05:00:00Z"));
    }

    /* ---------- (a) 버전 A→B — Viewer 이상 ---------- */

    @Test
    @DisplayName("버전 비교 — v2→v3 ALTER를 생성하고 감사를 남기지 않는다")
    void versionMigrationGeneratesWithoutAudit() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("mysql", V3)));
        given(modelVersionRepository.findByModelIdAndVersion(501L, 2L))
                .willReturn(Optional.of(snapshot(2L, V2)));
        given(modelVersionRepository.findByModelIdAndVersion(501L, 3L))
                .willReturn(Optional.of(snapshot(3L, V3)));

        MigrationDdlResponse response = service.generateVersionMigration(7L, 77L, 501L, 2L, 3L);

        verify(roleChecker).requireMember(7L, 77L);
        assertThat(response.sql())
                .contains("-- 회원 ERD — MySQL 마이그레이션 DDL (v2 → v3)")
                .contains("ALTER TABLE users ADD COLUMN grade VARCHAR(10);");
        assertThat(response.statementCount()).isEqualTo(1);
        assertThat(response.fromLabel()).isEqualTo("v2");
        assertThat(response.toLabel()).isEqualTo("v3");
        assertThat(response.warnings()).isEmpty();
        verify(auditRecorder, never()).record(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("버전 비교 — from==to는 400 INVALID_REQUEST")
    void versionMigrationRejectsSameVersion() {
        assertThatThrownBy(() -> service.generateVersionMigration(7L, 77L, 501L, 3L, 3L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("버전 비교 — 없는 버전은 404 MODEL_VERSION_NOT_FOUND")
    void versionMigrationRejectsUnknownVersion() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("mysql", V3)));
        given(modelVersionRepository.findByModelIdAndVersion(501L, 2L))
                .willReturn(Optional.of(snapshot(2L, V2)));
        given(modelVersionRepository.findByModelIdAndVersion(501L, 9L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateVersionMigration(7L, 77L, 501L, 2L, 9L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_VERSION_NOT_FOUND));
    }

    @Test
    @DisplayName("버전 비교 — 없는 문서는 404 MODEL_NOT_FOUND")
    void versionMigrationRejectsUnknownModel() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateVersionMigration(7L, 77L, 501L, 2L, 3L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_NOT_FOUND));
    }

    /* ---------- (b) 실제 DB→문서 — Editor 이상 ---------- */

    @Test
    @DisplayName("DB 비교 — 조회 스키마→문서 ALTER를 생성하고 MODEL_MIGRATION_DDL_GENERATED 감사를 남긴다")
    void connectionMigrationGeneratesAndAudits() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("mysql", V3)));
        given(connectionRepository.findByIdAndWorkspaceId(11L, 77L))
                .willReturn(Optional.of(connection("mysql")));
        given(schemaIntrospectionService.introspectContent(any()))
                .willReturn(DB);

        MigrationDdlResponse response = service.generateConnectionMigration(7L, 77L, 501L, 11L);

        verify(roleChecker).requireEditor(7L, 77L);
        assertThat(response.sql())
                .contains("-- 회원 ERD — MySQL 마이그레이션 DDL (DB → 문서)")
                .contains("ALTER TABLE users ADD COLUMN grade VARCHAR(10);");
        assertThat(response.statementCount()).isEqualTo(1);
        assertThat(response.fromLabel()).isEqualTo("DB");
        assertThat(response.toLabel()).isEqualTo("문서");
        verify(auditRecorder).record(7L, "MODEL_MIGRATION_DDL_GENERATED", "MODEL", "501",
                Map.of("connectionId", "11", "statements", 1));
    }

    @Test
    @DisplayName("DB 비교 — 문서와 커넥션의 DBMS가 다르면 400(1.8 배포와 같은 검사)")
    void connectionMigrationRejectsDbmsMismatch() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("postgresql", V3)));
        given(connectionRepository.findByIdAndWorkspaceId(11L, 77L))
                .willReturn(Optional.of(connection("mysql")));

        assertThatThrownBy(() -> service.generateConnectionMigration(7L, 77L, 501L, 11L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(e.getMessage()).contains("다릅니다");
                });
    }

    @Test
    @DisplayName("DB 비교 — 없는 커넥션은 404 CONNECTION_NOT_FOUND")
    void connectionMigrationRejectsUnknownConnection() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("mysql", V3)));
        given(connectionRepository.findByIdAndWorkspaceId(11L, 77L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateConnectionMigration(7L, 77L, 501L, 11L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_FOUND));
    }
}
