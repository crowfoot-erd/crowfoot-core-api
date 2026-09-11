package net.java21.crowfoot.api.account.controller;

import net.java21.crowfoot.api.account.dto.AdminIdentityResponse;
import net.java21.crowfoot.api.account.dto.AdminProviderResponse;
import net.java21.crowfoot.api.account.dto.AdminUserResponse;
import net.java21.crowfoot.api.account.dto.AuditLogResponse;
import net.java21.crowfoot.api.account.dto.UpdateAdminProviderRequest;
import net.java21.crowfoot.api.account.service.AdminAuditService;
import net.java21.crowfoot.api.account.service.AdminCodeService;
import net.java21.crowfoot.api.account.service.AdminSessionService;
import net.java21.crowfoot.api.account.service.AdminUserService;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 API 경계 (08-core/05-account.md Section 2) — X-USER-ID 필수·페이징 메타·204·400·403 */
@WebMvcTest(AdminController.class)
class AdminControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserService adminUserService;
    @MockitoBean
    private AdminSessionService adminSessionService;
    @MockitoBean
    private AdminCodeService adminCodeService;
    @MockitoBean
    private AdminAuditService adminAuditService;

    @Test
    @DisplayName("X-USER-ID 헤더가 없으면 401 AUTH_TOKEN_INVALID — 관리자 경로도 필터 통과 필요")
    void missingUserIdHeaderIsUnauthorized() throws Exception {
        // when & then
        mockMvc.perform(get("/core/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_TOKEN_INVALID"))
                .andExpect(jsonPath("$.response").doesNotExist());
    }

    @Test
    @DisplayName("사용자 목록은 공통 페이징 메타(page·size·totalPages·totalCount)를 응답한다")
    void usersRespondsWithPagingMeta() throws Exception {
        // given
        given(adminUserService.users(eq(2L), eq(null), eq(null), eq(null)))
                .willReturn(ListApiResponse.paged(List.of(new AdminUserResponse(
                        3L, "kim@x.com", "김철수",
                        List.of(new AdminIdentityResponse("github", "48239157")), false,
                        null, Instant.parse("2026-09-04T01:00:00Z"))), 1, 20, 73));

        // when & then
        mockMvc.perform(get("/core/admin/users").header("X-USER-ID", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.totalCount").value(73))
                .andExpect(jsonPath("$.responses[0].userId").value("3"))
                .andExpect(jsonPath("$.responses[0].identities[0].provider").value("github"))
                .andExpect(jsonPath("$.responses[0].identities[0].providerUserId").value("48239157"))
                .andExpect(jsonPath("$.responses[0].withdrawnAt").isEmpty());
    }

    @Test
    @DisplayName("Admin이 아니면 403 PERMISSION_DENIED — 서비스(AdminGuard) 판정을 공통 포맷으로 렌더")
    void nonAdminIsForbidden() throws Exception {
        // given
        given(adminUserService.users(eq(5L), any(), any(), any()))
                .willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED, "관리자 권한이 없습니다"));

        // when & then
        mockMvc.perform(get("/core/admin/users").header("X-USER-ID", "5"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"))
                .andExpect(jsonPath("$.header.resultMessage").value("관리자 권한이 없습니다"));
    }

    @Test
    @DisplayName("사용자 상세는 단건 response로 응답한다")
    void userRespondsSingleItem() throws Exception {
        // given
        given(adminUserService.user(2L, "3")).willReturn(new AdminUserResponse(
                3L, null, "jenny",
                List.of(new AdminIdentityResponse("github", "48239157")), true,
                Instant.parse("2026-09-05T00:00:00Z"), Instant.parse("2026-09-04T01:00:00Z")));

        // when & then
        mockMvc.perform(get("/core/admin/users/3").header("X-USER-ID", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.userId").value("3"))
                .andExpect(jsonPath("$.response.identities[0].providerUserId").value("48239157"))
                .andExpect(jsonPath("$.response.admin").value(true))
                .andExpect(jsonPath("$.response.withdrawnAt").value("2026-09-05T00:00:00Z"));
    }

    @Test
    @DisplayName("sessions의 userId 누락은 400 INVALID_REQUEST")
    void sessionsWithoutUserIdIsBadRequest() throws Exception {
        // when & then
        mockMvc.perform(get("/core/admin/sessions").header("X-USER-ID", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("세션 폐기는 204 본문 없음")
    void revokeSessionIsNoContent() throws Exception {
        // when & then
        mockMvc.perform(delete("/core/admin/sessions/00000000-0000-0000-0000-000000000001")
                        .header("X-USER-ID", "2"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("providers PATCH body의 code 누락은 400 INVALID_REQUEST (@Valid)")
    void providerPatchWithoutCodeIsBadRequest() throws Exception {
        // when & then
        mockMvc.perform(patch("/core/admin/providers").header("X-USER-ID", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\": false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("providers PATCH는 수정된 항목 1건을 response로 응답한다")
    void providerPatchRespondsUpdatedItem() throws Exception {
        // given
        given(adminCodeService.updateProvider(eq(2L), any(UpdateAdminProviderRequest.class)))
                .willReturn(new AdminProviderResponse("google", "Google", false));

        // when & then
        mockMvc.perform(patch("/core/admin/providers").header("X-USER-ID", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"google\", \"isActive\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.code").value("google"))
                .andExpect(jsonPath("$.response.isActive").value(false));
    }

    @Test
    @DisplayName("감사 로그는 keyword·action을 서비스에 전달하고 파싱된 detail을 페이징 메타와 함께 응답한다")
    void auditLogsRespondWithParsedDetail() throws Exception {
        // given
        given(adminAuditService.logs(2L, "marco", "ROLE_UPDATED", 1, 20))
                .willReturn(ListApiResponse.paged(List.of(new AuditLogResponse(
                        118L, Instant.parse("2026-09-12T01:03:00Z"), 2L, "marco", "marco@x.com",
                        "ROLE_UPDATED", "ROLE", "2", Map.of("roleName", "ADMIN"), "1.2.3.4")), 1, 20, 148));

        // when & then
        mockMvc.perform(get("/core/admin/audit-logs").header("X-USER-ID", "2")
                        .queryParam("keyword", "marco")
                        .queryParam("action", "ROLE_UPDATED")
                        .queryParam("page", "1")
                        .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(148))
                .andExpect(jsonPath("$.responses[0].id").value("118"))
                .andExpect(jsonPath("$.responses[0].actorUserId").value(2))
                .andExpect(jsonPath("$.responses[0].actorEmail").value("marco@x.com"))
                .andExpect(jsonPath("$.responses[0].targetType").value("ROLE"))
                .andExpect(jsonPath("$.responses[0].detail.roleName").value("ADMIN"));
    }

    @Test
    @DisplayName("감사 로그의 시스템 행은 actor 필드가 null로 응답된다")
    void auditLogsSystemRowHasNullActor() throws Exception {
        // given
        given(adminAuditService.logs(eq(2L), any(), any(), any(), any()))
                .willReturn(ListApiResponse.paged(List.of(new AuditLogResponse(
                        117L, Instant.parse("2026-09-12T00:58:00Z"), null, null, null,
                        "TOKEN_REFRESHED", "TOKEN", "SYSTEM", null, null)), 8, 20, 148));

        // when & then
        mockMvc.perform(get("/core/admin/audit-logs").header("X-USER-ID", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].actorUserId").isEmpty())
                .andExpect(jsonPath("$.responses[0].detail").doesNotExist())
                .andExpect(jsonPath("$.responses[0].targetId").value("SYSTEM"));
    }
}
