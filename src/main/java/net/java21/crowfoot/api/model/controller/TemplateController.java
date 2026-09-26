package net.java21.crowfoot.api.model.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.model.dto.CloneFromTemplateRequest;
import net.java21.crowfoot.api.model.dto.ModelResponse;
import net.java21.crowfoot.api.model.dto.TemplateSummaryResponse;
import net.java21.crowfoot.api.model.service.TemplateService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 템플릿 API (08-core/09-templates.md) — 공개 목록 경로 /core/templates(무인증 — XUserIdFilter 제외,
 * Gateway 화이트리스트 GET /api/v1/core/templates)와 복제 경로 /core/workspaces/**(인증, Editor 이상).
 * 목록은 템플릿 워크스페이스의 문서를 내리고, 복제는 그중 하나를 이 워크스페이스의 새 문서로 만든다.
 */
@RestController
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    /** 템플릿 공개 목록 — 무인증, 설정이 없으면 빈 목록(존재 은닉). 카드는 메타만 쓴다(content 미포함) */
    @GetMapping("/core/templates")
    public ListApiResponse<TemplateSummaryResponse> list() {
        return ListApiResponse.of(templateService.list());
    }

    /** 템플릿 복제 — Editor 이상, 201 + Location(생성된 문서), 한 트랜잭션에서 content까지 복사 */
    @PostMapping("/core/workspaces/{workspace-id}/models/from-template")
    public ResponseEntity<ApiResponse<ModelResponse>> clone(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody CloneFromTemplateRequest request) {
        ModelResponse response =
                templateService.clone(CurrentUserHolder.get().userId(), workspaceId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId
                        + "/models/" + response.modelId()))
                .body(ApiResponse.success(response));
    }
}
