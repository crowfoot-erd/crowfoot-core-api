package net.java21.crowfoot.api.model.sqlimport;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SQL Import API 웹 계층 테스트 (08-core/02-model.md Section 1.12) — 경로·201 Location·검증. */
@WebMvcTest(SqlImportController.class)
class SqlImportControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SqlImportService sqlImportService;

    @Test
    @DisplayName("미리보기는 200 — 테이블 요약·skipped를 응답한다")
    void previewReturnsSummary() throws Exception {
        given(sqlImportService.preview(eq(7L), eq(77L), eq(new SqlImportPreviewRequest("mysql", "CREATE TABLE t (a INT)"))))
                .willReturn(new SqlImportPreviewResponse("mysql", 1, 0,
                        List.of(new SqlImportPreviewResponse.PreviewTable(
                                "t", "테이블", 1, List.of("a"), 0)),
                        List.of("CREATE INDEX idx_t_a ON t (a)")));

        mockMvc.perform(post("/core/workspaces/77/models/sql-import/preview")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"databaseType\":\"mysql\",\"ddl\":\"CREATE TABLE t (a INT)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.databaseType").value("mysql"))
                .andExpect(jsonPath("$.response.tableCount").value(1))
                .andExpect(jsonPath("$.response.relationshipCount").value(0))
                .andExpect(jsonPath("$.response.tables[0].name").value("t"))
                .andExpect(jsonPath("$.response.tables[0].primaryKeyColumns[0]").value("a"))
                .andExpect(jsonPath("$.response.skipped[0]").value("CREATE INDEX idx_t_a ON t (a)"));
    }

    @Test
    @DisplayName("생성은 201 + Location(외부 URI) + 문서 전체 형식을 응답한다")
    void importReturns201WithLocation() throws Exception {
        given(sqlImportService.importDocument(eq(7L), eq(77L), eq(new SqlImportRequest(
                "쇼핑 ERD", null, "mysql", "CREATE TABLE t (a INT)"))))
                .willReturn(new SqlImportResponse(new ModelResponse(
                        "501", "77", "쇼핑 ERD", null, "mysql", null,
                        "{\"schemaVersion\":1,\"tables\":[],\"relationships\":[]}", 0,
                        new UserRefResponse("7", "marco"),
                        Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z")),
                        1, 0, List.of()));

        mockMvc.perform(post("/core/workspaces/77/models/sql-import")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"쇼핑 ERD\",\"databaseType\":\"mysql\","
                                + "\"ddl\":\"CREATE TABLE t (a INT)\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/workspaces/77/models/501"))
                .andExpect(jsonPath("$.response.model.modelId").value("501"))
                .andExpect(jsonPath("$.response.model.sourceConnectionId").doesNotExist())
                .andExpect(jsonPath("$.response.tableCount").value(1))
                .andExpect(jsonPath("$.response.skipped").isEmpty());
    }

    @Test
    @DisplayName("ddl 누락·databaseType 누락은 400 INVALID_REQUEST이다")
    void rejectsMissingFields() throws Exception {
        mockMvc.perform(post("/core/workspaces/77/models/sql-import/preview")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"databaseType\":\"mysql\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/core/workspaces/77/models/sql-import")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"ddl\":\"CREATE TABLE t (a INT)\"}"))
                .andExpect(status().isBadRequest());
    }
}
