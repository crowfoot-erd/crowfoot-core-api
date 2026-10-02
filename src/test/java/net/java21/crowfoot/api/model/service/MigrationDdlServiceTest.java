package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.service.SchemaIntrospectionService;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.dto.MigrationDdlResponse;
import net.java21.crowfoot.api.model.dto.ModelDeployResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 마이그레이션 DDL 서비스 단위 테스트 (08-core/02-model.md §1.7.1·§1.15) —
 * (a) 버전 비교(Viewer·감사 없음)와 (b) 문서↔DB 비교(Editor·DBMS 일치 검사·감사)·
 * 차분 실행(재계산 문장 그대로·0문장 접속 생략·감사)을 검증한다.
 * 스키마 조회·문장 실행은 스텁으로 대체해 서비스 간 결계만 확인한다.
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
    @Mock
    private DdlStatementExecutor statementExecutor;

    private MigrationDdlService service;

    @BeforeEach
    void setUp() {
        service = new MigrationDdlService(modelRepository, modelVersionRepository, connectionRepository,
                schemaIntrospectionService, roleChecker, auditRecorder, MAPPER, statementExecutor,
                new net.java21.crowfoot.api.connection.service.McpApplyGuard(org.mockito.Mockito.mock(net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository.class)));
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
                    assertThat(e.getMessageKey()).isEqualTo("detail.migration.dbms-mismatch");
                    assertThat(e.getArgs()).containsExactly("postgresql", "mysql");
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

    /* ---------- (b) 차분 실행(1.15) — Editor 이상 ---------- */

    @Test
    @DisplayName("차분 실행 — 실행 시점에 재계산한 문장을 그대로 실행하고 MODEL_MIGRATION_EXECUTED 감사를 남긴다")
    void executeMigrationRunsRecomputedStatements() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("mysql", V3)));
        given(connectionRepository.findByIdAndWorkspaceId(11L, 77L))
                .willReturn(Optional.of(connection("mysql")));
        given(schemaIntrospectionService.introspectContent(any())).willReturn(DB);
        given(statementExecutor.execute(any(), anyList()))
                .willReturn(List.of(new ModelDeployResponse.Statement(
                        "ALTER TABLE users ADD COLUMN grade VARCHAR(10);", true, null)));

        ModelDeployResponse response = service.executeConnectionMigration(7L, 77L, 501L, 11L, false);

        verify(roleChecker).requireEditor(7L, 77L);
        // 클라이언트 SQL이 아니라 재계산한 diff 문장이 실행 단위로 내려간다
        ArgumentCaptor<List<String>> statementsCaptor = ArgumentCaptor.captor();
        verify(statementExecutor).execute(any(), statementsCaptor.capture());
        assertThat(statementsCaptor.getValue())
                .containsExactly("ALTER TABLE users ADD COLUMN grade VARCHAR(10);");
        assertThat(response.executedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isZero();
        assertThat(response.statements()).hasSize(1);
        assertThat(response.statements().get(0).ok()).isTrue();
        verify(auditRecorder).record(7L, "MODEL_MIGRATION_EXECUTED", "MODEL", "501",
                Map.of("connectionId", "11", "connectionName", "개발 DB", "executed", 1, "failed", 0,
                        "includeDestructive", false, "skippedDestructive", 0));
    }

    @Test
    @DisplayName("차분 실행 — 재계산 문장이 0개(이미 동일)면 접속 없이 빈 리포트를 돌려준다")
    void executeMigrationSkipsExecutorWhenNoStatements() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("mysql", V3)));
        given(connectionRepository.findByIdAndWorkspaceId(11L, 77L))
                .willReturn(Optional.of(connection("mysql")));
        // DB 스키마가 문서와 동일한 스냅샷 — diff 0문장
        given(schemaIntrospectionService.introspectContent(any())).willReturn(V3);

        ModelDeployResponse response = service.executeConnectionMigration(7L, 77L, 501L, 11L, false);

        // introspection이 도달성을 증명했다 — 2차 JDBC 접속은 없다
        verify(statementExecutor, never()).execute(any(), anyList());
        assertThat(response.executedCount()).isZero();
        assertThat(response.failedCount()).isZero();
        assertThat(response.statements()).isEmpty();
        verify(auditRecorder).record(7L, "MODEL_MIGRATION_EXECUTED", "MODEL", "501",
                Map.of("connectionId", "11", "connectionName", "개발 DB", "executed", 0, "failed", 0,
                        "includeDestructive", false, "skippedDestructive", 0));
    }

    @Test
    @DisplayName("차분 실행 — 삭제 문장은 기본으로 건너뛰고, 요청이 명시했을 때만 실행한다")
    void executeMigrationSkipsDestructiveByDefault() {
        // 데이터베이스(V3 — grade 컬럼 있음)를 문서(DB 스냅샷 — grade 없음)에 맞추면 컬럼 삭제 문장이 나온다
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("mysql", DB)));
        given(connectionRepository.findByIdAndWorkspaceId(11L, 77L))
                .willReturn(Optional.of(connection("mysql")));
        given(schemaIntrospectionService.introspectContent(any())).willReturn(V3);

        // 계획 — 삭제 문장을 따로 알려 준다
        MigrationDdlResponse plan = service.generateConnectionMigration(7L, 77L, 501L, 11L);
        assertThat(plan.destructiveStatements()).containsExactly("ALTER TABLE users DROP COLUMN grade;");

        // 기본 — 삭제 문장뿐이면 실행하지 않고 건너뛴 수만 알린다
        ModelDeployResponse skipped = service.executeConnectionMigration(7L, 77L, 501L, 11L, false);
        verify(statementExecutor, never()).execute(any(), anyList());
        assertThat(skipped.executedCount()).isZero();
        assertThat(skipped.skippedDestructive()).isEqualTo(1);

        // 명시 — 삭제 문장까지 실행한다
        given(statementExecutor.execute(any(), anyList()))
                .willReturn(List.of(new ModelDeployResponse.Statement("ALTER TABLE users DROP COLUMN grade;", true, null)));
        ModelDeployResponse executed = service.executeConnectionMigration(7L, 77L, 501L, 11L, true);
        ArgumentCaptor<List<String>> statementsCaptor = ArgumentCaptor.captor();
        verify(statementExecutor).execute(any(), statementsCaptor.capture());
        assertThat(statementsCaptor.getValue()).containsExactly("ALTER TABLE users DROP COLUMN grade;");
        assertThat(executed.executedCount()).isEqualTo(1);
        assertThat(executed.skippedDestructive()).isZero();
    }

    @Test
    @DisplayName("차분 실행 — 문서와 커넥션의 DBMS가 다르면 400(생성과 같은 검사)")
    void executeMigrationRejectsDbmsMismatch() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("postgresql", V3)));
        given(connectionRepository.findByIdAndWorkspaceId(11L, 77L))
                .willReturn(Optional.of(connection("mysql")));

        assertThatThrownBy(() -> service.executeConnectionMigration(7L, 77L, 501L, 11L, false))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(e.getMessageKey()).isEqualTo("detail.migration.dbms-mismatch");
                    assertThat(e.getArgs()).containsExactly("postgresql", "mysql");
                });
        verify(statementExecutor, never()).execute(any(), anyList());
    }

    @Test
    @DisplayName("차분 실행 — 없는 커넥션은 404 CONNECTION_NOT_FOUND")
    void executeMigrationRejectsUnknownConnection() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model("mysql", V3)));
        given(connectionRepository.findByIdAndWorkspaceId(11L, 77L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.executeConnectionMigration(7L, 77L, 501L, 11L, false))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_FOUND));
    }
}
