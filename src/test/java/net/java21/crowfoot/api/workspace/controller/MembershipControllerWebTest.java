package net.java21.crowfoot.api.workspace.controller;

import net.java21.crowfoot.api.workspace.dto.MembershipIdResponse;
import net.java21.crowfoot.api.workspace.dto.MyWorkspaceResponse;
import net.java21.crowfoot.api.workspace.service.MembershipService;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 멤버십 API HTTP 계약 검증 — /core/accounts/me/workspaces 경로,
 * 부여 201 Location(외부 URI), 회수 204, 후보 검색 400 공통 실패 포맷.
 */
@WebMvcTest(MembershipController.class)
class MembershipControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MembershipService membershipService;

    @Test
    @DisplayName("GET /core/accounts/me/workspaces — myRole을 포함한 목록을 응답한다")
    void myWorkspacesReturnsListWithMyRole() throws Exception {
        // given
        given(membershipService.myWorkspaces(7L)).willReturn(ListApiResponse.of(
                List.of(new MyWorkspaceResponse("77", "ERD 작업실", null, false, "OWNER", 1))));

        // when & then
        mockMvc.perform(get("/core/accounts/me/workspaces").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].workspaceId").value("77"))
                .andExpect(jsonPath("$.responses[0].myRole").value("OWNER"))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("부여는 201 + Location(외부 URI)으로 응답한다")
    void grantReturns201WithExternalLocation() throws Exception {
        // given
        given(membershipService.grant(eq(7L), eq(77L), any()))
                .willReturn(new MembershipIdResponse("501"));

        // when & then
        mockMvc.perform(post("/core/workspaces/77/memberships").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"granteeType\":\"USER\",\"userId\":\"8\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/v1/core/workspaces/77/memberships/501"))
                .andExpect(jsonPath("$.response.membershipId").value("501"));
    }

    @Test
    @DisplayName("회수는 본문 없이 204로 응답한다")
    void revokeReturns204() throws Exception {
        // when & then
        mockMvc.perform(delete("/core/workspaces/77/memberships/501").header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("후보 검색 keyword 2자 미만은 400 INVALID_REQUEST 공통 실패 포맷")
    void candidatesShortKeywordReturns400() throws Exception {
        // given
        given(membershipService.candidates(7L, 77L, "k", null))
                .willThrow(new BusinessException(ErrorCode.INVALID_REQUEST, "검색어는 2자 이상이어야 합니다"));

        // when & then
        mockMvc.perform(get("/core/workspaces/77/membership-candidates")
                        .header("X-USER-ID", "7").param("keyword", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.header.resultMessage").value("검색어는 2자 이상이어야 합니다"));
    }
}
