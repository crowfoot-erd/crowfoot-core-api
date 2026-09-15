package net.java21.crowfoot.api.model.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.model.dto.CreateShareRequest;
import net.java21.crowfoot.api.model.dto.GalleryShareResponse;
import net.java21.crowfoot.api.model.dto.ModelShareResponse;
import net.java21.crowfoot.api.model.dto.PublicShareResponse;
import net.java21.crowfoot.api.model.service.ShareService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 문서 공유 링크 API (08-core/02-model.md Section 1.10) — 관리 경로 /core/workspaces/**(인증)와
 * 공개 경로 /core/shares/**(무인증 — XUserIdFilter 제외, Gateway 화이트리스트).
 * 공개 조회는 토큰을 아는 누구나 문서를 읽기 전용으로 볼 수 있게 하고,
 * 공개 갤러리 목록은 현재 공유 중인 문서를 랜딩 페이지에 나열한다.
 */
@RestController
@RequiredArgsConstructor
public class ModelShareController {

    private final ShareService shareService;

    /** 링크 발급 — Editor 이상, 201 + Location(관리 리소스), 토큰으로 공개 주소를 만든다 */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/shares")
    public ResponseEntity<ApiResponse<ModelShareResponse>> create(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @Valid @RequestBody(required = false) CreateShareRequest request) {
        ModelShareResponse response =
                shareService.create(CurrentUserHolder.get().userId(), workspaceId, modelId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId
                        + "/models/" + modelId + "/shares/" + response.shareId()))
                .body(ApiResponse.success(response));
    }

    /** 링크 목록 — Editor 이상, 최근 발급순 */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/shares")
    public ListApiResponse<ModelShareResponse> list(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId) {
        List<ModelShareResponse> responses =
                shareService.list(CurrentUserHolder.get().userId(), workspaceId, modelId);
        return ListApiResponse.of(responses);
    }

    /** 링크 철회 — Editor 이상, 즉시 무효화 */
    @DeleteMapping("/core/workspaces/{workspace-id}/models/{model-id}/shares/{share-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("model-id") long modelId,
            @PathVariable("share-id") long shareId) {
        shareService.revoke(CurrentUserHolder.get().userId(), workspaceId, modelId, shareId);
    }

    /** 공유 문서 공개 조회 — 무인증, 기간 내 문서 메타 + content (읽기 전용) */
    @GetMapping("/core/shares/{token}")
    public ApiResponse<PublicShareResponse> resolve(@PathVariable("token") String token) {
        return ApiResponse.success(shareService.resolve(token));
    }

    /** 공유 갤러리 목록 — 무인증, 현재 공유 중인 문서의 메타(랜딩 페이지 카드) */
    @GetMapping("/core/shares")
    public ListApiResponse<GalleryShareResponse> gallery() {
        return ListApiResponse.of(shareService.gallery());
    }
}
