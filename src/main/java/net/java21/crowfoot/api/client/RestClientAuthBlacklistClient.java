package net.java21.crowfoot.api.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 블랙리스트 등록 RestClient 구현 — 내부망 직접 호출(Gateway 경유 없음).
 *
 * <p>호출 실패는 폐기·탈퇴 트랜잭션 실패로 이어진다(fail-closed — 등록 성공 후 커밋).
 * FeignClient 전환은 인증 서버 구현 시점에 검토한다 (01-architecture/api-design.md Section 11).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestClientAuthBlacklistClient implements AuthBlacklistClient {

    private final RestClient authRestClient;

    @Override
    public void registerSessionBlacklist(String sid) {
        try {
            authRestClient.post()
                    .uri("/internal/auth/blacklists")
                    .body(Map.of("sid", sid))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.error("블랙리스트 등록 실패(sid={}) — fail-closed로 폐기 트랜잭션을 중단한다", sid, ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "인증 서버 블랙리스트 등록에 실패했습니다 — 잠시 후 다시 시도하세요");
        }
    }
}
