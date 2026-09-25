package net.java21.crowfoot.common.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.support.DelegatingMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 구성 재현 — spring.messages 프로퍼티로 만들어지는 MessageSource 빈이
 * error 계열·validation 계열 키를 로케일로 실제로 해석하는지 검증한다(런타임 회귀 방어).
 */
class MessageSourceBootWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessageSourceAutoConfiguration.class))
            .withPropertyValues(
                    "spring.messages.basename=i18n/messages,i18n/validation/ValidationMessages",
                    "spring.messages.encoding=UTF-8",
                    "spring.messages.fallback-to-system-locale=false");

    @Test
    @DisplayName("Boot 자동구성 MessageSource가 application.yml과 동일 프로퍼티에서 error.* 키를 로케일 해석한다")
    void bootMessageSourceResolvesErrorKeys() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MessageSource.class);
            MessageSource source = context.getBean(MessageSource.class);
            assertThat(source).isNotInstanceOf(DelegatingMessageSource.class);
            assertThat(source.getMessage("error.community_post_not_found", null, null, Locale.JAPANESE))
                    .isEqualTo("投稿が見つかりません");
            assertThat(source.getMessage("error.community_post_not_found", null, null, Locale.ENGLISH))
                    .isEqualTo("Post not found");
        });
    }

    @Test
    @DisplayName("같은 빈이 validation.* 사용자 정의 키(우리 번들)도 해석한다 — hibernate 내장과 구별")
    void bootMessageSourceResolvesValidationKeys() {
        runner.run(context -> {
            MessageSource source = context.getBean(MessageSource.class);
            assertThat(source.getMessage("jakarta.validation.constraints.NotBlank.message", null, null, Locale.JAPANESE))
                    .isEqualTo("空にできません");
        });
    }
}
