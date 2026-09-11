package net.java21.crowfoot.api.internal.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.internal.dto.GetOrCreateUserRequest;
import net.java21.crowfoot.api.internal.dto.GetOrCreateUserResponse;
import net.java21.crowfoot.api.internal.service.InternalAccountService;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 전용 API — 사용자 확보(get-or-create). Gateway 라우팅 제외, 내부망에서만 연다.
 */
@RestController
@RequiredArgsConstructor
public class InternalUserController {

    private final InternalAccountService internalAccountService;

    @PostMapping("/internal/core/users:get-or-create")
    public ApiResponse<GetOrCreateUserResponse> getOrCreate(@Valid @RequestBody GetOrCreateUserRequest request) {
        return ApiResponse.success(internalAccountService.getOrCreate(request));
    }
}
