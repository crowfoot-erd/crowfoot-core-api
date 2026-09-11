package net.java21.crowfoot.api.internal.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.internal.dto.RegisterRefreshTokenRequest;
import net.java21.crowfoot.api.internal.dto.RotateRefreshTokenRequest;
import net.java21.crowfoot.api.internal.dto.RotateRefreshTokenResponse;
import net.java21.crowfoot.api.internal.service.InternalRefreshTokenService;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 전용 API — Refresh 저장소(등록·Rotation·폐기)와 세션(Refresh lineage) 폐기.
 * 폐기 응답은 204 본문 없음 — 폐기할 행이 없어도 성공(멱등)이다.
 */
@RestController
@RequiredArgsConstructor
public class InternalRefreshTokenController {

    private final InternalRefreshTokenService internalRefreshTokenService;

    @PostMapping("/internal/core/refresh-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRefreshTokenRequest request) {
        internalRefreshTokenService.register(request);
        return ApiResponse.success();
    }

    @PostMapping("/internal/core/refresh-tokens:rotate")
    public ApiResponse<RotateRefreshTokenResponse> rotate(@Valid @RequestBody RotateRefreshTokenRequest request) {
        return ApiResponse.success(internalRefreshTokenService.rotate(request));
    }

    @DeleteMapping("/internal/core/refresh-tokens/{jti}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable("jti") String jti) {
        internalRefreshTokenService.revokeJti(jti);
    }

    @DeleteMapping("/internal/core/sessions/{sid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable("sid") String sid) {
        internalRefreshTokenService.revokeSession(sid);
    }
}
