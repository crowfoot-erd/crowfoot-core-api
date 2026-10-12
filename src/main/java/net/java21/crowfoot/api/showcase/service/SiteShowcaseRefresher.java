package net.java21.crowfoot.api.showcase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 사이트 썸네일 정기 다시 찍기 배치 (08-core/19-site-showcase.md Section 3.10 — v1.42). 매시 17분(KST)에 찍은 지 7일이
 * 지난 사이트를 10개까지 다시 찍는다. 캡처 한 건이 최대 약 35초라 한 번에 6분을 넘지 않는다. core 레플리카가 함께 돌아도
 * 사이트마다 선점(claimCapture)하므로 같은 사이트를 두 번 찍지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteShowcaseRefresher {

    static final int BATCH = 10;

    private final SiteShowcaseService showcaseService;

    @Scheduled(cron = "0 17 * * * *", zone = "Asia/Seoul")
    public void refresh() {
        try {
            int refreshed = showcaseService.refreshStale(BATCH);
            if (refreshed > 0) {
                log.info("사이트 썸네일 정기 다시 찍기 — {}건", refreshed);
            }
        } catch (RuntimeException e) {
            log.warn("사이트 썸네일 정기 다시 찍기 실패: {}", e.toString());
        }
    }
}
