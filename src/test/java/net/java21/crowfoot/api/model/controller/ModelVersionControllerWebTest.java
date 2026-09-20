package net.java21.crowfoot.api.model.controller;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.model.dto.DdlWarningResponse;
import net.java21.crowfoot.api.model.dto.MigrationDdlResponse;
import net.java21.crowfoot.api.model.dto.ModelVersionDetailResponse;
import net.java21.crowfoot.api.model.dto.ModelVersionEntryResponse;
import net.java21.crowfoot.api.model.dto.RestoreModelVersionRequest;
import net.java21.crowfoot.api.model.dto.SaveContentResponse;
import net.java21.crowfoot.api.model.service.MigrationDdlService;
import net.java21.crowfoot.api.model.service.ModelVersionService;
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
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 버전 기록 API 웹 계층 테스트 (08-core/02-model.md Section 1.11) — 경로·페이징 포맷·검증·오류 코드. */
@WebMvcTest(ModelVersionController.class)
class ModelVersionControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelVersionService modelVersionService;

    @MockitoBean
    private MigrationDdlService migrationDdlService;

    @Test
    @DisplayName("목록은 최신순 요약 행(content 없음)을 페이징 포맷으로 응답한다")
    void listReturnsPagedEntries() throws Exception {
        given(modelVersionService.list(7L, 77L, 501L, null, 1, 20)).willReturn(ListApiResponse.paged(List.of(
                new ModelVersionEntryResponse(3, "{\"items\":[]}", "member 테이블 추가",
                        new UserRefResponse("7", "marco"), Instant.parse("2026-09-20T05:00:00Z")),
                new ModelVersionEntryResponse(2, null, null,
                        new UserRefResponse("7", "marco"), Instant.parse("2026-09-19T05:00:00Z"))),
                1, 20, 2));

        mockMvc.perform(get("/core/workspaces/77/models/501/versions")
                        .header("X-USER-ID", "7")
                        .queryParam("page", "1").queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].version").value(3))
                .andExpect(jsonPath("$.responses[0].memo").value("member 테이블 추가"))
                .andExpect(jsonPath("$.responses[0].createdBy.name").value("marco"))
                .andExpect(jsonPath("$.responses[0].content").doesNotExist())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    @DisplayName("목록 keyword 파라미터는 메모 검색어로 서비스에 전달된다")
    void listBindsKeywordParam() throws Exception {
        // page·size 정규화(1·20)는 서비스 몫 — 컨트롤러는 null 그대로 넘긴다
        given(modelVersionService.list(7L, 77L, 501L, "grade", null, null)).willReturn(ListApiResponse.paged(List.of(
                new ModelVersionEntryResponse(1, null, "grade 컬럼 추가",
                        new UserRefResponse("7", "marco"), Instant.parse("2026-09-20T05:00:00Z"))),
                1, 20, 1));

        mockMvc.perform(get("/core/workspaces/77/models/501/versions")
                        .header("X-USER-ID", "7")
                        .queryParam("keyword", "grade"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].memo").value("grade 컬럼 추가"))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("상세는 해당 시점 content 전문을 응답한다")
    void detailReturnsContent() throws Exception {
        given(modelVersionService.detail(7L, 77L, 501L, 2L)).willReturn(new ModelVersionDetailResponse(
                2, "{\"schemaVersion\":1}", "{\"items\":[]}", "초안",
                new UserRefResponse("7", "marco"), Instant.parse("2026-09-20T05:00:00Z")));

        mockMvc.perform(get("/core/workspaces/77/models/501/versions/2").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(2))
                .andExpect(jsonPath("$.response.content").value("{\"schemaVersion\":1}"))
                .andExpect(jsonPath("$.response.memo").value("초안"));
    }

    @Test
    @DisplayName("상세는 없는 버전이면 404 MODEL_VERSION_NOT_FOUND다")
    void detailReturns404ForUnknownVersion() throws Exception {
        willThrow(new BusinessException(ErrorCode.MODEL_VERSION_NOT_FOUND))
                .given(modelVersionService).detail(7L, 77L, 501L, 9L);

        mockMvc.perform(get("/core/workspaces/77/models/501/versions/9").header("X-USER-ID", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MODEL_VERSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("메모 편집은 갱신된 목록 행을 응답한다 — 명시적 null은 삭제")
    void updateMemoReturnsUpdatedEntry() throws Exception {
        given(modelVersionService.updateMemo(eq(7L), eq(77L), eq(501L), eq(2L), any()))
                .willReturn(new ModelVersionEntryResponse(2, null, null,
                        new UserRefResponse("7", "marco"), Instant.parse("2026-09-20T05:00:00Z")));

        mockMvc.perform(patch("/core/workspaces/77/models/501/versions/2/memo")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"memo\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(2))
                .andExpect(jsonPath("$.response.memo").doesNotExist());
    }

    @Test
    @DisplayName("복원은 저장 응답(새 version·일시)을 재사용한다")
    void restoreReturnsSaveContentResponse() throws Exception {
        given(modelVersionService.restore(7L, 77L, 501L, 2L, new RestoreModelVersionRequest(3)))
                .willReturn(new SaveContentResponse(4, Instant.parse("2026-09-20T06:00:00Z")));

        mockMvc.perform(post("/core/workspaces/77/models/501/versions/2/restore")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"baseVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(4))
                .andExpect(jsonPath("$.response.updatedAt").value("2026-09-20T06:00:00Z"));
    }

    @Test
    @DisplayName("복원은 버전 불일치면 409 VERSION_CONFLICT다")
    void restoreReturns409OnConflict() throws Exception {
        willThrow(new BusinessException(ErrorCode.VERSION_CONFLICT))
                .given(modelVersionService)
                .restore(7L, 77L, 501L, 2L, new RestoreModelVersionRequest(2));

        mockMvc.perform(post("/core/workspaces/77/models/501/versions/2/restore")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"baseVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("복원 요청의 baseVersion 누락은 400 INVALID_REQUEST이다")
    void restoreRejectsMissingBaseVersion() throws Exception {
        mockMvc.perform(post("/core/workspaces/77/models/501/versions/2/restore")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("버전 간 마이그레이션 DDL은 from 경로·to 쿼리를 받아 sql·레이블을 응답한다")
    void versionMigrationReturnsSql() throws Exception {
        given(migrationDdlService.generateVersionMigration(7L, 77L, 501L, 2L, 3L)).willReturn(
                new MigrationDdlResponse("-- MySQL 마이그레이션 DDL (v2 → v3)\n\n"
                                + "ALTER TABLE users ADD COLUMN grade VARCHAR(10);",
                        List.of(new DdlWarningResponse("DESTRUCTIVE", "파괴적 연산이 있습니다")), 1, "v2", "v3"));

        mockMvc.perform(get("/core/workspaces/77/models/501/versions/2/migration")
                        .header("X-USER-ID", "7")
                        .queryParam("to", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sql").value(
                        "-- MySQL 마이그레이션 DDL (v2 → v3)\n\nALTER TABLE users ADD COLUMN grade VARCHAR(10);"))
                .andExpect(jsonPath("$.response.warnings[0].code").value("DESTRUCTIVE"))
                .andExpect(jsonPath("$.response.statementCount").value(1))
                .andExpect(jsonPath("$.response.fromLabel").value("v2"))
                .andExpect(jsonPath("$.response.toLabel").value("v3"));
    }

    @Test
    @DisplayName("버전 간 마이그레이션 DDL은 to 누락이면 400이다")
    void versionMigrationRequiresToParam() throws Exception {
        mockMvc.perform(get("/core/workspaces/77/models/501/versions/2/migration")
                        .header("X-USER-ID", "7"))
                .andExpect(status().isBadRequest());
    }
}
