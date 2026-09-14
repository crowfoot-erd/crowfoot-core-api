package net.java21.crowfoot.api.model.controller;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.ModelSummaryResponse;
import net.java21.crowfoot.api.model.dto.ModelVersionResponse;
import net.java21.crowfoot.api.model.dto.SaveContentRequest;
import net.java21.crowfoot.api.model.dto.SaveContentResponse;
import net.java21.crowfoot.api.model.service.ModelService;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ERD 문서 API 웹 계층 테스트 (08-core/02-model.md) — 경로·201 Location·검증. */
@WebMvcTest(ModelController.class)
class ModelControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelService modelService;

    @MockitoBean
    private net.java21.crowfoot.api.model.service.DdlService ddlService;

    @MockitoBean
    private net.java21.crowfoot.api.model.service.DeployService deployService;

    @Test
    @DisplayName("생성은 201 + Location(외부 URI) + databaseType·캔버스 크기를 응답한다")
    void createReturns201WithLocation() throws Exception {
        // given
        given(modelService.create(eq(7L), eq(77L), eq(new net.java21.crowfoot.api.model.dto.CreateModelRequest(
                "주문 서비스 ERD", null, "postgresql"))))
                .willReturn(new ModelResponse("501", "77", "주문 서비스 ERD", null, "postgresql",
                        "{\"tables\":[],\"relationships\":[]}", 0,
                        new UserRefResponse("7", "marco"),
                        Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z")));

        // when & then
        mockMvc.perform(post("/core/workspaces/77/models").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"주문 서비스 ERD\",\"databaseType\":\"postgresql\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/workspaces/77/models/501"))
                .andExpect(jsonPath("$.response.modelId").value("501"))
                .andExpect(jsonPath("$.response.databaseType").value("postgresql"))
                .andExpect(jsonPath("$.response.content").exists());
    }

    @Test
    @DisplayName("생성 요청의 databaseType 누락은 400 INVALID_REQUEST이다")
    void createRejectsMissingDatabaseType() throws Exception {
        mockMvc.perform(post("/core/workspaces/77/models").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"주문 ERD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("목록은 페이징 포맷으로 요약(content 제외)을 응답한다 — keyword·page 바인딩")
    void listReturnsPagedSummaries() throws Exception {
        // given
        given(modelService.list(7L, 77L, "주문", 1, 20)).willReturn(ListApiResponse.paged(
                List.of(new ModelSummaryResponse("501", "77", "주문 서비스 ERD", null, "postgresql",
                        0, new UserRefResponse("7", "marco"),
                        Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"))),
                1, 20, 1));

        // when & then
        mockMvc.perform(get("/core/workspaces/77/models")
                        .header("X-USER-ID", "7")
                        .param("keyword", "주문")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].modelId").value("501"))
                .andExpect(jsonPath("$.responses[0].databaseType").value("postgresql"))
                .andExpect(jsonPath("$.responses[0].content").doesNotExist());
    }

    @Test
    @DisplayName("상세는 content를 포함한 전체 필드를 응답한다 (1.3)")
    void detailReturnsFullModel() throws Exception {
        // given
        given(modelService.detail(7L, 77L, 501L)).willReturn(new ModelResponse("501", "77", "주문 서비스 ERD",
                "설명", "postgresql", "{\"tables\":[],\"relationships\":[]}", 3,
                new UserRefResponse("7", "marco"),
                Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z")));

        // when & then
        mockMvc.perform(get("/core/workspaces/77/models/501").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.modelId").value("501"))
                .andExpect(jsonPath("$.response.databaseType").value("postgresql"))
                .andExpect(jsonPath("$.response.version").value(3))
                .andExpect(jsonPath("$.response.content").value("{\"tables\":[],\"relationships\":[]}"));
    }

    @Test
    @DisplayName("버전 경량 조회는 version·갱신 일시만 응답한다 (1.9) — content 미포함")
    void versionReturnsLightweightPayload() throws Exception {
        // given
        given(modelService.version(7L, 77L, 501L))
                .willReturn(new ModelVersionResponse(4, Instant.parse("2026-09-14T05:00:00Z")));

        // when & then
        mockMvc.perform(get("/core/workspaces/77/models/501/version").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(4))
                .andExpect(jsonPath("$.response.updatedAt").value("2026-09-14T05:00:00Z"))
                .andExpect(jsonPath("$.response.content").doesNotExist());
    }

    @Test
    @DisplayName("버전 경량 조회 — 모델이 없으면 404 MODEL_NOT_FOUND")
    void versionRejectsUnknownModel() throws Exception {
        given(modelService.version(7L, 77L, 501L)).willThrow(new BusinessException(ErrorCode.MODEL_NOT_FOUND));

        mockMvc.perform(get("/core/workspaces/77/models/501/version").header("X-USER-ID", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MODEL_NOT_FOUND"));
    }

    @Test
    @DisplayName("content 저장은 200 + 갱신 버전·일시를 응답한다 (1.5)")
    void saveContentReturnsUpdatedVersion() throws Exception {
        // given
        given(modelService.saveContent(7L, 77L, 501L, new SaveContentRequest(3, "{\"schemaVersion\":1}")))
                .willReturn(new SaveContentResponse(4, Instant.parse("2026-09-12T05:00:00Z")));

        // when & then
        mockMvc.perform(put("/core/workspaces/77/models/501/content").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"baseVersion\":3,\"content\":\"{\\\"schemaVersion\\\":1}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(4))
                .andExpect(jsonPath("$.response.updatedAt").value("2026-09-12T05:00:00Z"));
    }

    @Test
    @DisplayName("content 저장의 baseVersion 누락·빈 content는 400이다")
    void saveContentRejectsMissingBaseVersion() throws Exception {
        mockMvc.perform(put("/core/workspaces/77/models/501/content").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"{}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("버전 충돌 저장은 409 VERSION_CONFLICT로 매핑된다")
    void saveContentMapsVersionConflict() throws Exception {
        given(modelService.saveContent(eq(7L), eq(77L), eq(501L), any(SaveContentRequest.class)))
                .willThrow(new BusinessException(ErrorCode.VERSION_CONFLICT));

        mockMvc.perform(put("/core/workspaces/77/models/501/content").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"baseVersion\":1,\"content\":\"{}\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("DDL 생성은 스크립트·경고·개수를 응답한다 (1.7)")
    void ddlReturnsScriptWithWarnings() throws Exception {
        // given
        given(ddlService.generate(7L, 77L, 501L)).willReturn(new net.java21.crowfoot.api.model.dto.ModelDdlResponse(
                "-- 주문 서비스 ERD — PostgreSQL DDL",
                List.of(new net.java21.crowfoot.api.model.dto.DdlWarningResponse(
                        "EMPTY_TABLE", "컬럼이 없어 생성에서 제외한 테이블: empty_one")),
                3, 2));

        // when & then
        mockMvc.perform(get("/core/workspaces/77/models/501/ddl").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sql").value("-- 주문 서비스 ERD — PostgreSQL DDL"))
                .andExpect(jsonPath("$.response.warnings[0].code").value("EMPTY_TABLE"))
                .andExpect(jsonPath("$.response.warnings[0].message").value("컬럼이 없어 생성에서 제외한 테이블: empty_one"))
                .andExpect(jsonPath("$.response.tableCount").value(3))
                .andExpect(jsonPath("$.response.relationshipCount").value(2));
    }

    @Test
    @DisplayName("DDL 생성 — 모델이 없으면 404 MODEL_NOT_FOUND")
    void ddlMapsModelNotFound() throws Exception {
        given(ddlService.generate(7L, 77L, 501L)).willThrow(new BusinessException(ErrorCode.MODEL_NOT_FOUND));

        mockMvc.perform(get("/core/workspaces/77/models/501/ddl").header("X-USER-ID", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MODEL_NOT_FOUND"));
    }

    @Test
    @DisplayName("배포는 문장별 실행 결과를 응답한다 (1.8) — 부분 실패 포함")
    void deployReturnsStatementResults() throws Exception {
        // given
        given(deployService.deploy(7L, 77L, 501L, 9L)).willReturn(
                new net.java21.crowfoot.api.model.dto.ModelDeployResponse(2, 1,
                        List.of(new net.java21.crowfoot.api.model.dto.ModelDeployResponse.Statement(
                                        "CREATE TABLE member (...)", true, null),
                                new net.java21.crowfoot.api.model.dto.ModelDeployResponse.Statement(
                                        "CREATE TABLE orders (...)", true, null),
                                new net.java21.crowfoot.api.model.dto.ModelDeployResponse.Statement(
                                        "ALTER TABLE orders ADD CONSTRAINT ...", false,
                                        "relation \"orders\" already exists")),
                        List.of()));

        // when & then
        mockMvc.perform(post("/core/workspaces/77/models/501/deploy").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"connectionId\":\"9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.executedCount").value(2))
                .andExpect(jsonPath("$.response.failedCount").value(1))
                .andExpect(jsonPath("$.response.statements[0].ok").value(true))
                .andExpect(jsonPath("$.response.statements[2].ok").value(false))
                .andExpect(jsonPath("$.response.statements[2].error").value("relation \"orders\" already exists"));
    }

    @Test
    @DisplayName("배포 — connectionId가 숫자가 아니면 400이다")
    void deployRejectsNonNumericConnectionId() throws Exception {
        mockMvc.perform(post("/core/workspaces/77/models/501/deploy").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"connectionId\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }
}
