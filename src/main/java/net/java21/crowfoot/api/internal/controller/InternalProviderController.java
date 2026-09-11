package net.java21.crowfoot.api.internal.controller;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.dto.ProviderResponse;
import net.java21.crowfoot.api.account.service.ProviderService;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 내부 전용 API — 활성 제공자 조회(로그인 시작·콜백의 활성 검증).
 * 데이터는 프론트용 공개 API(GET /core/providers)와 같다(활성만).
 */
@RestController
@RequiredArgsConstructor
public class InternalProviderController {

    private final ProviderService providerService;

    @GetMapping("/internal/core/providers")
    public ListApiResponse<ProviderResponse> listActive() {
        List<ProviderResponse> providers = providerService.listActive();
        return ListApiResponse.of(providers);
    }
}
