package net.java21.crowfoot.api.account.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.Provider;
import net.java21.crowfoot.api.account.dto.ProviderResponse;
import net.java21.crowfoot.api.account.repository.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 활성 제공자 목록 — 로그인 화면 버튼 구성(공개 API)과 인증 서버의 활성 검증(내부 API)이 같은 데이터를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class ProviderService {

    private final ProviderRepository providerRepository;

    @Transactional(readOnly = true)
    public List<ProviderResponse> listActive() {
        return providerRepository.findByIsActiveTrueOrderByCodeAsc().stream()
                .map(provider -> new ProviderResponse(provider.getCode(), provider.getDisplayName()))
                .toList();
    }
}
