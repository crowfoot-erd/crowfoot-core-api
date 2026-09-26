package net.java21.crowfoot.api.metrics.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 비콘 판정 분류기 테스트 (08-core/10-metrics.md Section 4) — 봇·UA 파생 차원·경로 정규화·리퍼러. */
class VisitClassifierTest {

    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final String EDGE_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36 Edg/126.0";
    private static final String SAFARI_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    private static final String FIREFOX_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:127.0) Gecko/20100101 Firefox/127.0";
    private static final String CHROME_ANDROID_TABLET =
            "Mozilla/5.0 (Linux; Android 13; SM-X910) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final VisitClassifier classifier = new VisitClassifier();

    @Test
    @DisplayName("봇 판정 — UA 없음·크롤러·curl은 봇, 일반 브라우저는 아니다")
    void botDetection() {
        assertThat(classifier.isBot(null)).isTrue();
        assertThat(classifier.isBot("")).isTrue();
        assertThat(classifier.isBot("Mozilla/5.0 (compatible; Googlebot/2.1; +http://google.com/bot.html)")).isTrue();
        assertThat(classifier.isBot("curl/8.7.1")).isTrue();
        assertThat(classifier.isBot("python-requests/2.31")).isTrue();
        assertThat(classifier.isBot(CHROME_WINDOWS)).isFalse();
    }

    @Test
    @DisplayName("브라우저 — Edge는 Chrome 토큰을 포함하지만 edge로 판정된다(순서 보장)")
    void browserClassification() {
        assertThat(classifier.browser(EDGE_WINDOWS)).isEqualTo("edge");
        assertThat(classifier.browser(CHROME_WINDOWS)).isEqualTo("chrome");
        assertThat(classifier.browser(FIREFOX_MAC)).isEqualTo("firefox");
        assertThat(classifier.browser(SAFARI_IPHONE)).isEqualTo("safari");
        assertThat(classifier.browser("lynx/2.9")).isEqualTo("other");
    }

    @Test
    @DisplayName("OS·디바이스 — iPhone=ios/mobile, iPad·안드로이드(모바일 토큰 없음)=tablet, 데스크톱 조합")
    void osAndDeviceClassification() {
        assertThat(classifier.os(CHROME_WINDOWS)).isEqualTo("windows");
        assertThat(classifier.os(SAFARI_IPHONE)).isEqualTo("ios");
        assertThat(classifier.os(FIREFOX_MAC)).isEqualTo("macos");
        assertThat(classifier.os(CHROME_ANDROID_TABLET)).isEqualTo("android");

        assertThat(classifier.device(SAFARI_IPHONE)).isEqualTo("mobile");
        assertThat(classifier.device("Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) Safari/604.1")).isEqualTo("tablet");
        assertThat(classifier.device(CHROME_ANDROID_TABLET)).isEqualTo("tablet");
        assertThat(classifier.device(CHROME_WINDOWS)).isEqualTo("desktop");
        assertThat(classifier.device(FIREFOX_MAC)).isEqualTo("desktop");
    }

    @Test
    @DisplayName("언어 — Accept-Language 첫 태그의 주 언어만(ko-KR→ko), 미지원·없음은 other")
    void langClassification() {
        assertThat(classifier.lang("ko-KR,ko;q=0.9,en-US;q=0.8")).isEqualTo("ko");
        assertThat(classifier.lang("en-US,en;q=0.9")).isEqualTo("en");
        assertThat(classifier.lang("zh-TW,zh;q=0.8")).isEqualTo("zh");
        assertThat(classifier.lang("fr-FR,fr;q=0.9")).isEqualTo("other");
        assertThat(classifier.lang(null)).isEqualTo("other");
    }

    @Test
    @DisplayName("리퍼러 — 없음=direct, 사이트 도메인=internal, 타 사이트는 등록 도메인만, 파싱 불가=other")
    void referrerDomainExtraction() {
        assertThat(classifier.referrerDomain(null)).isEqualTo("direct");
        assertThat(classifier.referrerDomain("")).isEqualTo("direct");
        assertThat(classifier.referrerDomain("https://crowfoot.java21.net/workspaces")).isEqualTo("internal");
        assertThat(classifier.referrerDomain("https://www.google.com/search?q=erd")).isEqualTo("google.com");
        assertThat(classifier.referrerDomain("https://blog.naver.com/abc/123")).isEqualTo("naver.com");
        assertThat(classifier.referrerDomain("not a url")).isEqualTo("other");
    }

    @Test
    @DisplayName("경로 정규화 — 언어 prefix 제거 후 폐쇄 집합 매핑, 미지원 경로는 other")
    void pathGroupMapping() {
        assertThat(classifier.pathGroup("/")).isEqualTo(new VisitClassifier.PathInfo("/", null));
        assertThat(classifier.pathGroup("/en")).isEqualTo(new VisitClassifier.PathInfo("/", null));
        assertThat(classifier.pathGroup("/ja/login/")).isEqualTo(new VisitClassifier.PathInfo("/login", null));
        assertThat(classifier.pathGroup("/workspaces")).isEqualTo(new VisitClassifier.PathInfo("/workspaces", null));
        assertThat(classifier.pathGroup("/workspaces/77")).isEqualTo(new VisitClassifier.PathInfo("/workspaces/*", null));
        assertThat(classifier.pathGroup("/workspaces/77/models/501")).isEqualTo(new VisitClassifier.PathInfo("/workspaces/*/models/*", null));
        assertThat(classifier.pathGroup("/teams/5")).isEqualTo(new VisitClassifier.PathInfo("/teams/*", null));
        assertThat(classifier.pathGroup("/community")).isEqualTo(new VisitClassifier.PathInfo("/community", null));
        assertThat(classifier.pathGroup("/release-notes/v1.18")).isEqualTo(new VisitClassifier.PathInfo("/release-notes/*", null));
        assertThat(classifier.pathGroup("/admin/traffic")).isEqualTo(new VisitClassifier.PathInfo("/admin/*", null));
        assertThat(classifier.pathGroup("/favicon.ico")).isEqualTo(new VisitClassifier.PathInfo("other", null));
    }

    @Test
    @DisplayName("공유 뷰어 — 그룹 /share/*와 함께 토큰을 별도 차원으로 돌려준다(빈 토큰은 null)")
    void shareTokenExtraction() {
        assertThat(classifier.pathGroup("/share/tok123"))
                .isEqualTo(new VisitClassifier.PathInfo("/share/*", "tok123"));
        assertThat(classifier.pathGroup("/en/share/tok123/extra"))
                .isEqualTo(new VisitClassifier.PathInfo("/share/*", "tok123"));
        assertThat(classifier.pathGroup("/share/"))
                .isEqualTo(new VisitClassifier.PathInfo("/share/*", null));
    }
}
