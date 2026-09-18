package net.java21.crowfoot.api.account.controller;

import net.java21.crowfoot.api.account.dto.MeResponse;
import net.java21.crowfoot.api.account.service.AccountService;
import net.java21.crowfoot.api.account.service.ProviderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** X-USER-ID 필터·공통 응답 포맷 경계 (AUTH-18) — /core/accounts/me 보호, /core/providers 공개 */
@WebMvcTest(AccountController.class)
class AccountControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;
    @MockitoBean
    private ProviderService providerService;

    @Test
    @DisplayName("X-USER-ID 헤더가 있으면 내 프로필을 공통 포맷으로 응답한다")
    void meWithUserIdHeader() throws Exception {
        // given
        given(accountService.me(7L)).willReturn(new MeResponse(
                "7", "alice@x.com", "앨리스", "https://avatars.githubusercontent.com/u/77?v=4", "octocat",
                List.of("github"), false, Instant.parse("2026-09-01T00:00:00Z")));

        // when & then
        mockMvc.perform(get("/core/accounts/me").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.resultCode").value("SUCCESS"))
                .andExpect(jsonPath("$.response.userId").value("7"))
                .andExpect(jsonPath("$.response.avatarUrl").value("https://avatars.githubusercontent.com/u/77?v=4"))
                .andExpect(jsonPath("$.response.githubLogin").value("octocat"))
                .andExpect(jsonPath("$.response.providers[0]").value("github"));
    }

    @Test
    @DisplayName("X-USER-ID 헤더가 없으면 401 AUTH_TOKEN_INVALID (response 필드 생략)")
    void missingUserIdHeaderIsUnauthorized() throws Exception {
        // when & then
        mockMvc.perform(get("/core/accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_TOKEN_INVALID"))
                .andExpect(jsonPath("$.response").doesNotExist());
    }

    @Test
    @DisplayName("X-USER-ID가 숫자가 아니면 401")
    void nonNumericUserIdHeaderIsUnauthorized() throws Exception {
        // when & then
        mockMvc.perform(get("/core/accounts/me").header("X-USER-ID", "abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("providers는 인증 없이(헤더 없이) 200 — 공개 엔드포인트")
    void providersIsPublic() throws Exception {
        // given
        given(providerService.listActive()).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/core/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.responses").isArray())
                .andExpect(jsonPath("$.totalCount").value(0));
    }
}
