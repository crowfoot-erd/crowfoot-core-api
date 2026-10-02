package net.java21.crowfoot.api.accesstoken.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.IssueRequest;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.TokenResponse;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.VerifyRequest;
import net.java21.crowfoot.api.accesstoken.dto.AccessTokenDtos.VerifyResponse;
import net.java21.crowfoot.api.accesstoken.service.AccessTokenService;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 워크스페이스 액세스 토큰 API (08-core/18-access-token.md Section 3).
 * 발급·목록·폐기는 구현 경로 /core/**, 검증은 내부 전용 /internal/**(Gateway 라우팅 제외 — 인증 서버가 부른다).
 */
@RestController
@RequiredArgsConstructor
public class AccessTokenController {

    private final AccessTokenService service;

    /** 목록 — 멤버는 자기 토큰만, Owner는 전체 */
    @GetMapping("/core/workspaces/{workspace-id}/access-tokens")
    public ListApiResponse<TokenResponse> list(@PathVariable("workspace-id") long workspaceId) {
        return ListApiResponse.of(service.list(CurrentUserHolder.get().userId(), workspaceId));
    }

    /** 발급 — 멤버 누구나, 201. 원문은 이 응답에서만 나온다 */
    @PostMapping("/core/workspaces/{workspace-id}/access-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TokenResponse> issue(@PathVariable("workspace-id") long workspaceId,
                                            @Valid @RequestBody IssueRequest request) {
        return ApiResponse.success(service.issue(CurrentUserHolder.get().userId(), workspaceId, request));
    }

    /** 폐기 — 발급한 본인 또는 Owner, 204 */
    @DeleteMapping("/core/workspaces/{workspace-id}/access-tokens/{token-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable("workspace-id") long workspaceId, @PathVariable("token-id") long tokenId) {
        service.revoke(CurrentUserHolder.get().userId(), workspaceId, tokenId);
    }

    /** 검증 — 내부 전용. 인증 서버가 Introspection 중에 부른다 */
    @PostMapping("/internal/core/access-tokens/verify")
    public ApiResponse<VerifyResponse> verify(@Valid @RequestBody VerifyRequest request) {
        return ApiResponse.success(service.verify(request.tokenHash()));
    }
}
