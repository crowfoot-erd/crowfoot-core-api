package net.java21.crowfoot.api.workspace.controller;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.workspace.dto.WorkspaceResponse;
import net.java21.crowfoot.api.workspace.service.WorkspaceService;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Workspace API HTTP 계약 검증 — 하이픈 path variable 바인딩,
 * 201 Location(외부 URI)·204, BusinessException의 공통 실패 포맷 변환.
 */
@WebMvcTest(WorkspaceController.class)
class WorkspaceControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkspaceService workspaceService;

    @Test
    @DisplayName("GET /core/workspaces/77 — 하이픈 경로 변수가 Long으로 바인딩된다")
    void getBindsHyphenPathVariable() throws Exception {
        // given
        given(workspaceService.get(7L, 77L)).willReturn(workspace());

        // when & then
        mockMvc.perform(get("/core/workspaces/77").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.workspaceId").value("77"))
                .andExpect(jsonPath("$.response.memberCount").value(3))
                .andExpect(jsonPath("$.response.createdBy.name").value("앨리스"));
        verify(workspaceService).get(7L, 77L);
    }

    @Test
    @DisplayName("비멤버의 조회는 404 공통 실패 포맷으로 변환된다 — response 필드 생략")
    void getReturnsCommonFailureFormatFor404() throws Exception {
        // given
        given(workspaceService.get(7L, 77L))
                .willThrow(new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/core/workspaces/77").header("X-USER-ID", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("WORKSPACE_NOT_FOUND"))
                .andExpect(jsonPath("$.response").doesNotExist());
    }

    @Test
    @DisplayName("생성은 201 + Location(외부 URI /api/v1/core/...)로 응답한다")
    void createReturns201WithExternalLocation() throws Exception {
        // given
        given(workspaceService.create(eq(7L), any())).willReturn(workspace());

        // when & then
        mockMvc.perform(post("/core/workspaces").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"ERD 작업실\",\"description\":\"설명\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/workspaces/77"))
                .andExpect(jsonPath("$.response.workspaceId").value("77"));
    }

    @Test
    @DisplayName("Owner가 아닌 설정 변경은 403 공통 실패 포맷(덮어쓴 문구)으로 응답한다")
    void patchReturnsCommonFailureFormatFor403() throws Exception {
        // given
        given(workspaceService.patch(eq(7L), eq(77L), any()))
                .willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED, "설정 변경 권한이 없습니다"));

        // when & then
        mockMvc.perform(patch("/core/workspaces/77").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"새 이름\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"))
                .andExpect(jsonPath("$.header.resultMessage").value("설정 변경 권한이 없습니다"));
    }

    @Test
    @DisplayName("삭제는 본문 없이 204로 응답한다")
    void deleteReturns204() throws Exception {
        // when & then
        mockMvc.perform(delete("/core/workspaces/77").header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
        verify(workspaceService).delete(7L, 77L);
    }

    private WorkspaceResponse workspace() {
        return new WorkspaceResponse("77", "ERD 작업실", "설명", false, 3,
                new UserRefResponse("7", "앨리스"), Instant.parse("2026-09-01T00:00:00Z"));
    }
}
