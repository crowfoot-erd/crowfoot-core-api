package net.java21.crowfoot.api.account.controller;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.dto.MeResponse;
import net.java21.crowfoot.api.account.dto.ProviderResponse;
import net.java21.crowfoot.api.account.service.AccountService;
import net.java21.crowfoot.api.account.service.ProviderService;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 계정 API (08-core/05-account.md Section 1) — 구현 경로 /core/**
 * (Gateway URL Rewrite 후 — 외부 계약은 /api/v1/core/*).
 */
@RestController
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final ProviderService providerService;

    @GetMapping("/core/accounts/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.success(accountService.me(CurrentUserHolder.get().userId()));
    }

    /** 회원 탈퇴(soft) — 본문 없음 */
    @DeleteMapping("/core/accounts/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw() {
        accountService.withdraw(CurrentUserHolder.get().userId());
    }

    /** 활성 제공자 목록 — 공개(로그인 버튼 구성) */
    @GetMapping("/core/providers")
    public ListApiResponse<ProviderResponse> providers() {
        List<ProviderResponse> providers = providerService.listActive();
        return ListApiResponse.of(providers);
    }
}
