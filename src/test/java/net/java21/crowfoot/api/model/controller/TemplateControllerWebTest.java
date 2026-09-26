package net.java21.crowfoot.api.model.controller;

import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.model.dto.CloneFromTemplateRequest;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.TemplateSummaryResponse;
import net.java21.crowfoot.api.model.service.TemplateService;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 템플릿 API 웹 계층 테스트 (08-core/09-templates.md) — 공개 목록 무인증·복제 201 Location·오류 매핑. */
@WebMvcTest(TemplateController.class)
class TemplateControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TemplateService templateService;

    @Test
    @DisplayName("공개 목록은 X-USER-ID 없이도 200 — content 없이 메타만 내려준다")
    void listIsPublicWithoutUserId() throws Exception {
        given(templateService.list()).willReturn(List.of(
                new TemplateSummaryResponse("501", "쇼핑몰 ERD", "상품·주문·결제", "postgresql",
                        20, 24, "tokA", Instant.parse("2026-09-26T09:00:00Z")),
                new TemplateSummaryResponse("502", "블로그 ERD", null, "mysql",
                        12, 9, null, Instant.parse("2026-09-25T09:00:00Z"))));

        mockMvc.perform(get("/core/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].modelId").value("501"))
                .andExpect(jsonPath("$.responses[0].tableCount").value(20))
                .andExpect(jsonPath("$.responses[0].relationshipCount").value(24))
                .andExpect(jsonPath("$.responses[0].shareToken").value("tokA"))
                .andExpect(jsonPath("$.responses[1].description").doesNotExist())
                .andExpect(jsonPath("$.responses[1].shareToken").doesNotExist())
                .andExpect(jsonPath("$.responses[0].content").doesNotExist())
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    @Test
    @DisplayName("복제는 201 + Location(생성된 문서) + 문서 전체 형식을 응답한다")
    void cloneReturns201WithLocation() throws Exception {
        given(templateService.clone(eq(7L), eq(77L), eq(new CloneFromTemplateRequest(501L, "내 쇼핑몰 ERD"))))
                .willReturn(new ModelResponse("601", "77", "내 쇼핑몰 ERD", "상품·주문·결제", "postgresql",
                        null, "{\"schemaVersion\":1}", 0, new UserRefResponse("7", "marco"),
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-26T00:00:00Z")));

        mockMvc.perform(post("/core/workspaces/77/models/from-template")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"templateModelId\":501,\"name\":\"내 쇼핑몰 ERD\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/workspaces/77/models/601"))
                .andExpect(jsonPath("$.response.modelId").value("601"))
                .andExpect(jsonPath("$.response.databaseType").value("postgresql"))
                .andExpect(jsonPath("$.response.sourceConnectionId").doesNotExist());
    }

    @Test
    @DisplayName("복제는 X-USER-ID 없이는 401로 거부된다 — Gateway 경유 요청만 받는다")
    void cloneRequiresUserId() throws Exception {
        mockMvc.perform(post("/core/workspaces/77/models/from-template")
                        .contentType(APPLICATION_JSON)
                        .content("{\"templateModelId\":501}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("복제는 templateModelId 누락이면 400 INVALID_REQUEST이다")
    void cloneRejectsMissingTemplateModelId() throws Exception {
        mockMvc.perform(post("/core/workspaces/77/models/from-template")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("복제는 템플릿 밖의 문서면 404 TEMPLATE_NOT_FOUND다")
    void cloneReturns404ForUnknownTemplate() throws Exception {
        willThrow(new BusinessException(ErrorCode.TEMPLATE_NOT_FOUND))
                .given(templateService).clone(eq(7L), eq(77L), eq(new CloneFromTemplateRequest(999L, null)));

        mockMvc.perform(post("/core/workspaces/77/models/from-template")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"templateModelId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("TEMPLATE_NOT_FOUND"));
    }

    @Test
    @DisplayName("복제는 이름 중복이면 409 DUPLICATED_NAME이다")
    void cloneReturns409ForDuplicatedName() throws Exception {
        willThrow(BusinessException.of(ErrorCode.DUPLICATED_NAME, "detail.doc-name.duplicated"))
                .given(templateService).clone(eq(7L), eq(77L), eq(new CloneFromTemplateRequest(501L, null)));

        mockMvc.perform(post("/core/workspaces/77/models/from-template")
                        .header("X-USER-ID", "7")
                        .contentType(APPLICATION_JSON)
                        .content("{\"templateModelId\":501}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("DUPLICATED_NAME"));
    }

    @Test
    @DisplayName("복제는 Viewer면 403 PERMISSION_DENIED다")
    void cloneReturns403ForViewer() throws Exception {
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .given(templateService).clone(eq(9L), eq(77L), eq(new CloneFromTemplateRequest(501L, null)));

        mockMvc.perform(post("/core/workspaces/77/models/from-template")
                        .header("X-USER-ID", "9")
                        .contentType(APPLICATION_JSON)
                        .content("{\"templateModelId\":501}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
    }
}
