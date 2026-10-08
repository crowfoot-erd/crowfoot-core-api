package net.java21.crowfoot.api.showcase.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.showcase.dto.PublicSiteResponse;
import net.java21.crowfoot.api.showcase.dto.ReportSiteRequest;
import net.java21.crowfoot.api.showcase.dto.SaveSiteRequest;
import net.java21.crowfoot.api.showcase.dto.SiteResponse;
import net.java21.crowfoot.api.showcase.repository.SiteThumbnail;
import net.java21.crowfoot.api.showcase.service.SiteShowcaseService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 사이트 쇼케이스 API (08-core/19-site-showcase.md) — 관리 경로 /core/workspaces/**(인증)와
 * 공개 경로 /core/showcase/**(선택 인증 — XUserIdFilter, 목록·썸네일만 Gateway 화이트리스트, 신고는 로그인).
 */
@RestController
@RequiredArgsConstructor
public class SiteShowcaseController {

    private final SiteShowcaseService showcaseService;

    /** 문서의 사이트 — 멤버, 없으면 response null (3.1) */
    @GetMapping("/core/workspaces/{workspace-id}/models/{model-id}/site")
    public ApiResponse<SiteResponse> get(@PathVariable("workspace-id") long workspaceId,
                                         @PathVariable("model-id") long modelId) {
        return ApiResponse.success(showcaseService.get(CurrentUserHolder.get().userId(), workspaceId, modelId));
    }

    /** 등록·수정 — Editor 이상, 처음이거나 주소가 바뀌면 캡처(최대 약 35초) (3.2) */
    @PutMapping("/core/workspaces/{workspace-id}/models/{model-id}/site")
    public ApiResponse<SiteResponse> save(@PathVariable("workspace-id") long workspaceId,
                                          @PathVariable("model-id") long modelId,
                                          @Valid @RequestBody SaveSiteRequest request) {
        return ApiResponse.success(showcaseService.save(CurrentUserHolder.get().userId(), workspaceId, modelId, request));
    }

    /** 다시 가져오기 — Editor 이상, 1분에 한 번 (3.3) */
    @PostMapping("/core/workspaces/{workspace-id}/models/{model-id}/site/capture")
    public ApiResponse<SiteResponse> recapture(@PathVariable("workspace-id") long workspaceId,
                                               @PathVariable("model-id") long modelId) {
        return ApiResponse.success(showcaseService.recapture(CurrentUserHolder.get().userId(), workspaceId, modelId));
    }

    /** 삭제 — Editor 이상 (3.4) */
    @DeleteMapping("/core/workspaces/{workspace-id}/models/{model-id}/site")
    public ApiResponse<Void> delete(@PathVariable("workspace-id") long workspaceId,
                                    @PathVariable("model-id") long modelId) {
        showcaseService.delete(CurrentUserHolder.get().userId(), workspaceId, modelId);
        return ApiResponse.success();
    }

    /** 공개 목록 — 무인증, 최근 등록순 (3.5) */
    @GetMapping("/core/showcase/sites")
    public ListApiResponse<PublicSiteResponse> list(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "12") int size) {
        Page<PublicSiteResponse> result = showcaseService.publicList(page, size);
        return ListApiResponse.paged(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    /** 썸네일 — 무인증, 하루 캐시. 주소의 v가 바뀌면 새 그림 (3.6) */
    @GetMapping("/core/showcase/sites/{site-id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable("site-id") long siteId) {
        SiteThumbnail thumbnail = showcaseService.thumbnail(siteId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(thumbnail.type()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(thumbnail.image());
    }

    /** 신고 — 로그인, 멱등 (3.7). XUserIdFilter가 헤더 없는 요청을 401로 막는다 */
    @PostMapping("/core/showcase/sites/{site-id}/reports")
    public ApiResponse<Map<String, Boolean>> report(@PathVariable("site-id") long siteId,
                                                    @Valid @RequestBody(required = false) ReportSiteRequest request) {
        showcaseService.report(CurrentUserHolder.get().userId(), siteId, request == null ? null : request.reason());
        return ApiResponse.success(Map.of("reported", true));
    }
}
