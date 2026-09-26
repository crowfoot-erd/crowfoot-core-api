package net.java21.crowfoot.api.metrics.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 비콘 판정의 순수 분류기 (08-core/10-metrics.md Section 4) — 봇 여부, 브라우저/OS/디바이스/언어
 * 파싱, 리퍼러 도메인 추출, 경로 정규화(path_group + share_token). 폐쇄 집합 밖은 전부 other로
 * 떨어진다(카디널리티 통제).
 */
@Component
public class VisitClassifier {

    /** 봇 UA 폐쇄 목록 — Section 4.1. UA 없음도 봇으로 본다 */
    private static final Pattern BOT_UA = Pattern.compile(
            "bot|crawl|spider|slurp|bingpreview|lighthouse|headless|phantom|puppeteer|playwright"
                    + "|curl|wget|python-requests|monitor",
            Pattern.CASE_INSENSITIVE);

    /** 사이트 자체 도메인 — 유입 아님(internal) */
    private static final String INTERNAL_REFERRER_SUFFIX = "java21.net";

    private static final Pattern LANG_PREFIX = Pattern.compile("^/(?:en|ja|zh)(?=/|$)");

    public record PathInfo(String pathGroup, String shareToken) {
    }

    public boolean isBot(String userAgent) {
        return userAgent == null || userAgent.isBlank() || BOT_UA.matcher(userAgent).find();
    }

    /** 브라우저 — Edge가 Chrome 토큰을 포함하므로 Edg/를 먼저 판정(Section 4.3) */
    public String browser(String userAgent) {
        if (userAgent == null) {
            return "other";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("edg/") || ua.contains("edge/")) {
            return "edge";
        }
        if (ua.contains("firefox") || ua.contains("fxios")) {
            return "firefox";
        }
        if (ua.contains("chrome") || ua.contains("crios")) {
            return "chrome";
        }
        if (ua.contains("safari")) {
            return "safari";
        }
        return "other";
    }

    public String os(String userAgent) {
        if (userAgent == null) {
            return "other";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("windows")) {
            return "windows";
        }
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) {
            return "ios";
        }
        if (ua.contains("android")) {
            return "android";
        }
        if (ua.contains("mac os x") || ua.contains("macintosh")) {
            return "macos";
        }
        if (ua.contains("linux") || ua.contains("x11")) {
            return "linux";
        }
        return "other";
    }

    /** 디바이스 — 태블릿 판정을 모바일보다 먼저(안드로이드는 둘 다 포함할 수 있다) */
    public String device(String userAgent) {
        if (userAgent == null) {
            return "other";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        boolean android = ua.contains("android");
        if (ua.contains("ipad") || ua.contains("tablet") || (android && !ua.contains("mobile"))) {
            return "tablet";
        }
        if (ua.contains("iphone") || ua.contains("ipod") || ua.contains("mobile") || android) {
            return "mobile";
        }
        return switch (os(userAgent)) {
            case "windows", "macos", "linux" -> "desktop";
            default -> "other";
        };
    }

    /** Accept-Language 첫 태그 → ko/en/ja/zh/other(지역 하위 태그는 버린다) */
    public String lang(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return "other";
        }
        String first = acceptLanguage.split("[,;]")[0].trim().toLowerCase(Locale.ROOT);
        String primary = first.split("[-_]")[0];
        return switch (primary) {
            case "ko", "en", "ja", "zh" -> primary;
            default -> "other";
        };
    }

    /**
     * 리퍼러 → 등록 도메인만. 없음·빈 값은 direct, 사이트 자체 도메인은 internal(유입 아님),
     * 파싱 불가는 other. 전체 URL은 저장하지 않는다(/share 토큰 유출 방지 — Section 2).
     */
    public String referrerDomain(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return "direct";
        }
        try {
            String host = URI.create(referrer.trim()).getHost();
            if (host == null) {
                return "other";
            }
            String domain = host.toLowerCase(Locale.ROOT);
            if (domain.endsWith(INTERNAL_REFERRER_SUFFIX)) {
                return "internal";
            }
            String[] labels = domain.split("\\.");
            if (labels.length <= 2) {
                return domain;
            }
            // 등록 도메인 근사 — 마지막 2라벨(co.uk 같은 2차 TLD는 근사로 흡수)
            return labels[labels.length - 2] + "." + labels[labels.length - 1];
        } catch (IllegalArgumentException ex) {
            return "other";
        }
    }

    /**
     * 경로 정규화 — 언어 prefix(/en·/ja·/zh) 제거 후 매핑(Section 4.4).
     * 공유 뷰어는 그룹 /share/*와 함께 토큰을 별도 차원으로 돌려준다.
     */
    public PathInfo pathGroup(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return new PathInfo("other", null);
        }
        String path = LANG_PREFIX.matcher(rawPath).replaceFirst("");
        if (path.isEmpty()) {
            // 언어 prefix만 있는 경로(/en·/ja·/zh)는 루트 페이지다
            path = "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.equals("/") || path.equals("/login") || path.equals("/terms")
                || path.equals("/workspaces") || path.equals("/community")) {
            return new PathInfo(path, null);
        }
        if (path.startsWith("/workspaces/")) {
            String rest = path.substring("/workspaces/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String after = rest.substring(slash + 1);          // {models|connections|terms|memberships}/...
                if (after.startsWith("models/") || after.equals("models")) {
                    return new PathInfo("/workspaces/*/models/*", null);
                }
            }
            return new PathInfo("/workspaces/*", null);
        }
        if (path.startsWith("/teams/")) {
            return new PathInfo("/teams/*", null);
        }
        if (path.startsWith("/release-notes/")) {
            return new PathInfo("/release-notes/*", null);
        }
        if (path.startsWith("/share")) {
            // 빈 토큰(/share·/share/)도 그룹에는 속한다 — 토큰 차원만 null
            String token = path.length() > "/share/".length() ? path.substring("/share/".length()) : "";
            int slash = token.indexOf('/');
            if (slash >= 0) {
                token = token.substring(0, slash);
            }
            return new PathInfo("/share/*", token.isEmpty() ? null : token);
        }
        if (path.startsWith("/admin/")) {
            return new PathInfo("/admin/*", null);
        }
        return new PathInfo("other", null);
    }
}
