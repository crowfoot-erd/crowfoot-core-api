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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
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

    /** 최근 조회한 공유 문서 쿠키 — 30분 창 안 재조회의 조회 수 제외용(1.10.4), 값 규격은 ViewedShares */
    static final String VIEWED_COOKIE = "crowfoot_share_views";
    private static final String VIEWED_COOKIE_PATH = "/api/v1/core/shares";

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

    /** 공유 문서 공개 조회 — 무인증, 기간 내 문서 메타 + content (읽기 전용).
     *  조회 수는 {@link ViewedShares#WINDOW} 창을 지난 첫 조회만 올린다 — 같은 방문자의 재조회(새로고침
     *  연타)는 crowfoot_share_views 쿠키로 제외하고, 창을 다시 돌린 토큰 목록을 Set-Cookie로 되돌린다 */
    @GetMapping("/core/shares/{token}")
    public ResponseEntity<ApiResponse<PublicShareResponse>> resolve(
            @PathVariable("token") String token,
            @CookieValue(name = VIEWED_COOKIE, required = false) String viewed) {
        Instant now = Instant.now();
        ViewedShares viewedShares = ViewedShares.parse(viewed, now);
        PublicShareResponse response =
                shareService.resolve(token, !viewedShares.withinWindow(token, now));
        // 쿠키 Path는 브라우저가 보는 외부 경로 기준이라 /api/v1 아래로 둔다(RefreshCookieWriter와 같은 근거).
        // 운영은 항상 https이고 로컬 localhost도 신뢰 컨텍스트라 Secure 고정 — HttpOnly는 안 읽히게(조작 무의미)
        ResponseCookie cookie = ResponseCookie.from(VIEWED_COOKIE, viewedShares.with(token, now))
                .path(VIEWED_COOKIE_PATH)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(ViewedShares.WINDOW)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success(response));
    }

    /** 공유 갤러리 목록 — 무인증, 현재 공유 중인 문서의 메타(랜딩 페이지 카드) */
    @GetMapping("/core/shares")
    public ListApiResponse<GalleryShareResponse> gallery() {
        return ListApiResponse.of(shareService.gallery());
    }
}
