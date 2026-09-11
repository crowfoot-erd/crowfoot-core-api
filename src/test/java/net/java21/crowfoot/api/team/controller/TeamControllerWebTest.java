package net.java21.crowfoot.api.team.controller;

import net.java21.crowfoot.api.team.dto.CreateTeamResponse;
import net.java21.crowfoot.api.team.dto.TeamDetailResponse;
import net.java21.crowfoot.api.team.service.TeamService;
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
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 팀 API HTTP 계약 검증 — 생성 201(teamId 응답),
 * 멤버 추가 201 header-only, 해체 204, 존재 은닉 404 공통 실패 포맷.
 */
@WebMvcTest(TeamController.class)
class TeamControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TeamService teamService;

    @Test
    @DisplayName("생성은 201 + Location(외부 URI) + teamId를 응답한다")
    void createReturns201WithId() throws Exception {
        // given
        given(teamService.create(eq(7L), any()))
                .willReturn(new CreateTeamResponse("31"));

        // when & then
        mockMvc.perform(post("/core/teams").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"플랫폼팀\",\"description\":\"설명\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/teams/31"))
                .andExpect(jsonPath("$.response.teamId").value("31"));
    }

    @Test
    @DisplayName("GET /core/teams/31 — 하이픈 경로 변수가 바인딩되고 myRole을 응답한다")
    void getReturnsDetailWithMyRole() throws Exception {
        // given
        given(teamService.get(7L, 31L)).willReturn(new TeamDetailResponse(
                "31", "플랫폼팀", "설명", true, "7", 2, "OWNER",
                Instant.parse("2026-09-01T00:00:00Z")));

        // when & then
        mockMvc.perform(get("/core/teams/31").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.teamId").value("31"))
                .andExpect(jsonPath("$.response.myRole").value("OWNER"))
                .andExpect(jsonPath("$.response.memberCount").value(2));
    }

    @Test
    @DisplayName("비소속 팀 조회는 404 TEAM_NOT_FOUND 공통 실패 포맷으로 은닉된다")
    void getReturnsCommonFailureFormatFor404() throws Exception {
        // given
        given(teamService.get(7L, 31L))
                .willThrow(new BusinessException(ErrorCode.TEAM_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/core/teams/31").header("X-USER-ID", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("TEAM_NOT_FOUND"))
                .andExpect(jsonPath("$.response").doesNotExist());
    }

    @Test
    @DisplayName("멤버 추가는 201 header-only — 바디 없음")
    void addMemberReturns201HeaderOnly() throws Exception {
        // given
        given(teamService.addMember(eq(7L), eq(31L), any())).willReturn(8L);

        // when & then
        mockMvc.perform(post("/core/teams/31/members").header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"userId\":\"8\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("해체는 본문 없이 204로 응답한다")
    void dissolveReturns204() throws Exception {
        // when & then
        mockMvc.perform(delete("/core/teams/31").header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
    }
}
