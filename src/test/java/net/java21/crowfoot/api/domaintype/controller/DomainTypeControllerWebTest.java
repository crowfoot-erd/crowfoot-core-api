package net.java21.crowfoot.api.domaintype.controller;

import net.java21.crowfoot.api.domaintype.dto.DomainTypeRequest;
import net.java21.crowfoot.api.domaintype.dto.DomainTypeResponse;
import net.java21.crowfoot.api.domaintype.service.DomainTypeService;
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
import static org.mockito.BDDMockito.then;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 도메인 타입 API 웹 계층 테스트 (08-core/16-domain-type.md Section 3) — 경로·상태 코드·본문 검증. */
@WebMvcTest(DomainTypeController.class)
class DomainTypeControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DomainTypeService domainTypeService;

    private static DomainTypeResponse email(int version) {
        return new DomainTypeResponse("11", "77", "이메일", "VARCHAR", 191, null, null, false, null,
                "로그인에 쓰는 주소", version, Instant.parse("2026-10-02T00:00:00Z"));
    }

    @Test
    @DisplayName("목록은 200 — 페이징 메타 없는 목록 형식")
    void lists() throws Exception {
        given(domainTypeService.list(eq(7L), eq(77L))).willReturn(List.of(email(3)));

        mockMvc.perform(get("/core/workspaces/77/domain-types").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].domainTypeId").value("11"))
                .andExpect(jsonPath("$.responses[0].name").value("이메일"))
                .andExpect(jsonPath("$.responses[0].dataType").value("VARCHAR"))
                .andExpect(jsonPath("$.responses[0].length").value(191))
                .andExpect(jsonPath("$.responses[0].nullable").value(false))
                .andExpect(jsonPath("$.responses[0].version").value(3));
    }

    @Test
    @DisplayName("만들기는 201")
    void creates() throws Exception {
        given(domainTypeService.create(eq(7L), eq(77L), any(DomainTypeRequest.class))).willReturn(email(1));

        mockMvc.perform(post("/core/workspaces/77/domain-types")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"이메일\",\"dataType\":\"VARCHAR\",\"length\":191,\"nullable\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("고치기는 200 — baseVersion을 서비스로 넘긴다")
    void updates() throws Exception {
        given(domainTypeService.update(eq(7L), eq(77L), eq(11L), any(DomainTypeRequest.class))).willReturn(email(4));

        mockMvc.perform(put("/core/workspaces/77/domain-types/11")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"이메일\",\"dataType\":\"VARCHAR\",\"length\":255,\"baseVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(4));
        then(domainTypeService).should().update(eq(7L), eq(77L), eq(11L),
                eq(new DomainTypeRequest("이메일", "VARCHAR", 255, null, null, null, null, null, 3)));
    }

    @Test
    @DisplayName("지우기는 204")
    void deletes() throws Exception {
        mockMvc.perform(delete("/core/workspaces/77/domain-types/11").header("X-USER-ID", "7"))
                .andExpect(status().isNoContent());
        then(domainTypeService).should().delete(7L, 77L, 11L);
    }

    @Test
    @DisplayName("본문 검증 — 이름 없음·타입 형식 오류·음수 길이·51자 이름은 400이고 서비스에 닿지 않는다")
    void validatesBody() throws Exception {
        String[] bodies = {
                "{\"dataType\":\"VARCHAR\"}",
                "{\"name\":\"이메일\",\"dataType\":\"varchar(10)\"}",
                "{\"name\":\"이메일\",\"dataType\":\"VARCHAR\",\"length\":-1}",
                "{\"name\":\"" + "가".repeat(51) + "\",\"dataType\":\"VARCHAR\"}",
        };
        for (String body : bodies) {
            mockMvc.perform(post("/core/workspaces/77/domain-types")
                            .header("X-USER-ID", "7")
                            .contentType(APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        }
        then(domainTypeService).shouldHaveNoInteractions();
    }
}
