package net.java21.crowfoot.api.model.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.Optional;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.connection.service.SchemaIntrospectionService;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.repository.ModelRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 동기화 서비스 단위 테스트 (08-core/02-model.md §1.16) — Editor 권한, 원천 커넥션 확인, 계획 지문 대조(409),
 * 변경이 없으면 저장하지 않음, 저장 시 변경 요약(source: sync)과 감사. 스키마 조회와 저장은 스텁이다.
 */
@ExtendWith(MockitoExtension.class)
class DocumentSyncServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DOC = """
            {"schemaVersion":1,"model":{"tables":[
              {"id":"t1","logicalName":"회원","physicalName":"users","comment":null,"primaryKey":null,"uniques":[],"indexes":[],"checks":[],"columns":[
                {"id":"c1","logicalName":"id","physicalName":"id","dataType":"BIGINT","length":null,"precision":null,"scale":null,
                 "nullable":false,"defaultValue":null,"autoIncrement":true,"comment":null,"generated":null,"onUpdate":null}]},
              {"id":"t2","logicalName":"old","physicalName":"old_table","comment":null,"primaryKey":null,"uniques":[],"indexes":[],"checks":[],"columns":[]}],
              "relationships":[]},"diagram":{"nodes":{},"notes":[],"viewport":null}}
            """;

    private static final String DB = """
            {"schemaVersion":1,"model":{"tables":[
              {"id":"x1","logicalName":"users","physicalName":"users","comment":null,"primaryKey":null,"uniques":[],"indexes":[],"checks":[],"columns":[
                {"id":"xc1","logicalName":"id","physicalName":"id","dataType":"BIGINT","length":null,"precision":null,"scale":null,
                 "nullable":false,"defaultValue":null,"autoIncrement":true,"comment":null,"generated":null,"onUpdate":null},
                {"id":"xc2","logicalName":"email","physicalName":"email","dataType":"VARCHAR","length":100,"precision":null,"scale":null,
                 "nullable":false,"defaultValue":null,"autoIncrement":false,"comment":null,"generated":null,"onUpdate":null}]}],
              "relationships":[]},"diagram":{"nodes":{},"notes":[],"viewport":null}}
            """;

    private static final String DEV_KEY = java.util.Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Mock
    private ModelRepository modelRepository;
    @Mock
    private DbConnectionRepository connectionRepository;
    @Mock
    private SchemaIntrospectionService schemaIntrospectionService;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private DocumentSyncWriter writer;

    private DocumentSyncService service;
    private DbConnection connection;

    @BeforeEach
    void setUp() {
        service = new DocumentSyncService(modelRepository, connectionRepository, schemaIntrospectionService, roleChecker,
                auditRecorder, MAPPER, writer);
        connection = new DbConnection(77L, "개발 DB", "mysql", "db.dev", 3306, "crowfoot", null, "crowfoot",
                new ConnectionCrypto(DEV_KEY).encrypt("pw"), 7L);
        ReflectionTestUtils.setField(connection, "id", 11L);
    }

    private void stub(Long sourceConnectionId) {
        Model model = new Model(77L, "회원 ERD", null, "mysql", DOC, 7L);
        ReflectionTestUtils.setField(model, "id", 501L);
        ReflectionTestUtils.setField(model, "version", 4L);
        ReflectionTestUtils.setField(model, "sourceConnectionId", sourceConnectionId);
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
        given(connectionRepository.findByIdAndWorkspaceId(11L, 77L)).willReturn(Optional.of(connection));
    }

    @Test
    @DisplayName("계획 — Editor 권한으로 items·removals·지문을 돌려주고 아무것도 저장하지 않는다")
    void plan() {
        stub(11L);
        given(schemaIntrospectionService.introspectContentForComparison(connection)).willReturn(DB);

        DocumentSyncService.PlanResponse plan = service.plan(7L, 77L, 501L, 11L);

        verify(roleChecker).requireEditor(7L, 77L);
        assertThat(plan.items()).extracting(DocumentSync.Item::name).containsExactly("email");
        assertThat(plan.removals()).extracting(DocumentSync.Item::name).containsExactly("old_table");
        assertThat(plan.changeCount()).isEqualTo(1);
        assertThat(plan.removalCount()).isEqualTo(1);
        assertThat(plan.planFingerprint()).hasSize(64);
        assertThat(plan.version()).isEqualTo(4L);
        verify(writer, never()).save(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("원천 커넥션이 아닌 커넥션과는 동기화하지 않는다(400)")
    void rejectsOtherConnection() {
        stub(12L);

        assertThatThrownBy(() -> service.plan(7L, 77L, 501L, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("적용 — 지문이 다르면 409 SYNC_PLAN_CHANGED로 거부하고 저장하지 않는다")
    void rejectsChangedPlan() {
        stub(11L);
        given(schemaIntrospectionService.introspectContentForComparison(connection)).willReturn(DB);

        assertThatThrownBy(() -> service.apply(7L, 77L, 501L, 11L, new DocumentSyncService.ApplyRequest("stale", false)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SYNC_PLAN_CHANGED);
        verify(writer, never()).save(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("적용 — 삭제 없이 새 버전으로 저장하고 변경 요약(source: sync)과 감사를 남긴다")
    void applySavesNewVersion() {
        stub(11L);
        given(schemaIntrospectionService.introspectContentForComparison(connection)).willReturn(DB);
        String fingerprint = service.plan(7L, 77L, 501L, 11L).planFingerprint();
        given(writer.save(eq(77L), eq(501L), eq(4L), anyString(), anyString(), eq(7L))).willReturn(5L);

        DocumentSyncService.ApplyResponse response = service.apply(7L, 77L, 501L, 11L,
                new DocumentSyncService.ApplyRequest(fingerprint, null));

        assertThat(response.changed()).isTrue();
        assertThat(response.version()).isEqualTo(5L);
        assertThat(response.modelId()).isEqualTo("501");
        assertThat(response.removals()).isEmpty();
        assertThat(response.skippedRemovals()).isEqualTo(1);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(writer).save(eq(77L), eq(501L), eq(4L), content.capture(), summary.capture(), eq(7L));
        JsonNode saved = MAPPER.readTree(content.getValue());
        assertThat(saved.path("model").path("tables")).hasSize(2);
        assertThat(saved.path("model").path("tables").get(0).path("logicalName").asString()).isEqualTo("회원");
        JsonNode changeSummary = MAPPER.readTree(summary.getValue());
        assertThat(changeSummary.path("source").asString()).isEqualTo("sync");
        assertThat(changeSummary.path("items").get(0).path("kind").asString()).isEqualTo("column");
        verify(auditRecorder).record(eq(7L), eq("MODEL_SYNCED_FROM_DB"), eq("MODEL"), eq("501"), any(Map.class));
    }

    @Test
    @DisplayName("적용 — includeRemovals=true면 문서에만 있는 테이블도 지운다")
    void applyWithRemovals() {
        stub(11L);
        given(schemaIntrospectionService.introspectContentForComparison(connection)).willReturn(DB);
        String fingerprint = service.plan(7L, 77L, 501L, 11L).planFingerprint();
        given(writer.save(eq(77L), eq(501L), eq(4L), anyString(), anyString(), eq(7L))).willReturn(5L);

        DocumentSyncService.ApplyResponse response = service.apply(7L, 77L, 501L, 11L,
                new DocumentSyncService.ApplyRequest(fingerprint, true));

        assertThat(response.removals()).extracting(DocumentSync.Item::name).containsExactly("old_table");
        assertThat(response.skippedRemovals()).isZero();
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(writer).save(eq(77L), eq(501L), eq(4L), content.capture(), anyString(), eq(7L));
        assertThat(MAPPER.readTree(content.getValue()).path("model").path("tables")).hasSize(1);
    }
}
