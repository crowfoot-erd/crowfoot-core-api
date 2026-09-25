package net.java21.crowfoot.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * 다국어 메시지 정적 브리지 (api-design.md §5.7) — Spring 빈이 아닌 정적 유틸
 * (JdbcDiagnostics·DDL 경고 조립 등)에서도 Accept-Language 로케일 문구를 쓸 수 있게 한다.
 *
 * <p>{@link net.java21.crowfoot.common.web.WebLocaleConfig}가 기동 시 MessageSource를 주입한다.
 * 주입 전(단위 테스트·슬라이스 테스트)이거나 키가 없으면 전달받은 기본 문구(한국어)를 그대로 돌려준다 —
 * 호출부는 번들 유무와 무관하게 항상 유효한 문구를 얻는다.
 */
public final class ServerMessages {

    private static volatile MessageSource source;

    private ServerMessages() {
    }

    /** 기동 시 1회 주입 (WebLocaleConfig) */
    public static void init(MessageSource messageSource) {
        source = messageSource;
    }

    /** 테스트 격리용 */
    static void reset() {
        source = null;
    }

    /** 키 해석 — 주입 안 됐거나 키가 없으면 fallback. 로케일은 LocaleContextHolder(요청 스레드) */
    public static String resolve(String key, Object[] args, String fallback) {
        MessageSource messageSource = source;
        if (messageSource == null || key == null) {
            return fallback;
        }
        String resolved = messageSource.getMessage(key, args, null, currentLocale());
        return resolved != null ? resolved : fallback;
    }

    private static Locale currentLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale == null ? Locale.KOREAN : locale;
    }
}
