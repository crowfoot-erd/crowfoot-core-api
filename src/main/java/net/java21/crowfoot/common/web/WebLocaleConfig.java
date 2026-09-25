package net.java21.crowfoot.common.web;

import net.java21.crowfoot.common.i18n.ServerMessages;
import org.springframework.context.MessageSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * 요청 로케일 구성 (api-design.md §5.7) — Accept-Language(ko/en/ja/zh)를 LocaleContextHolder로
 * 노출한다. 미지원 언어·헤더 없음은 ko. MessageSource(spring.messages)를 검증 문구와
 * {@link ServerMessages} 정적 브리지에 공급한다.
 */
@Configuration(proxyBeanMethods = false)
public class WebLocaleConfig implements WebMvcConfigurer, InitializingBean {

    private final MessageSource messageSource;

    public WebLocaleConfig(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.KOREAN, Locale.ENGLISH, Locale.JAPANESE,
                Locale.SIMPLIFIED_CHINESE));
        resolver.setDefaultLocale(Locale.KOREAN);
        return resolver;
    }

    /** 빈 검증 문구도 같은 MessageSource({validation.*} 키)로 요청 로케일 해석한다 */
    @Override
    public org.springframework.validation.Validator getValidator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        return validator;
    }

    /** 정적 유틸(JdbcDiagnostics·DDL 경고)에 번들 공급 — 기동 시 1회 */
    @Override
    public void afterPropertiesSet() {
        ServerMessages.init(messageSource);
    }
}
