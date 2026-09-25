package net.java21.crowfoot.common.i18n;

import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다국어 메시지 번들 패리티 (api-design.md §5.7) — 4개 언어 properties의 키 집합·빈값·placeholder가
 * 어긋나면 게이트 red. 파일을 직접 읽어 ResourceBundle 폴백 영향 없이 비교한다.
 */
class MessageParityTest {

    private static final List<String> LANGS = List.of("ko", "en", "ja", "zh");
    private static final String MESSAGES = "i18n/messages_%s.properties";
    private static final String VALIDATION = "i18n/validation/ValidationMessages_%s.properties";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[0-9A-Za-z.]+}");

    @Test
    @DisplayName("messages_{ko,en,ja,zh} — 키 집합·빈값·placeholder 집합이 4벌 모두 같다")
    void messagesBundlesAreInParity() {
        Map<String, Properties> bundles = loadAll(MESSAGES);

        Set<Object> koKeys = bundles.get("ko").keySet();
        for (String lang : LANGS) {
            assertThat(bundles.get(lang).keySet())
                    .as("messages_%s 키 집합이 ko와 다릅니다", lang)
                    .isEqualTo(koKeys);
        }
        for (String lang : LANGS) {
            for (Object key : bundles.get(lang).keySet()) {
                assertThat(((String) bundles.get(lang).get(key)).isBlank())
                        .as("messages_%s의 %s 값이 비었습니다", lang, key).isFalse();
            }
        }
        for (Object key : koKeys) {
            for (String lang : LANGS) {
                assertThat(placeholders((String) bundles.get(lang).get(key)))
                        .as("messages_%s의 %s placeholder 집합이 ko와 다릅니다", lang, key)
                        .isEqualTo(placeholders((String) bundles.get("ko").get(key)));
            }
        }
    }

    @Test
    @DisplayName("error.* 키는 ErrorCode 42종과 정확히 1:1이다 — 추가 코드는 4벌 동시 갱신 필요")
    void errorKeysMatchErrorCodeEnum() {
        Map<String, Properties> bundles = loadAll(MESSAGES);
        Set<String> expected = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::messageKey)
                .collect(Collectors.toCollection(TreeSet::new));

        for (String lang : LANGS) {
            Set<String> actual = bundles.get(lang).keySet().stream()
                    .map(key -> (String) key)
                    .filter(key -> key.startsWith("error."))
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(actual)
                    .as("messages_%s의 error.* 키가 ErrorCode enum과 다릅니다", lang)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("ValidationMessages_{ko,en,ja,zh} — 키 집합·빈값·placeholder 집합이 4벌 모두 같다")
    void validationBundlesAreInParity() {
        Map<String, Properties> bundles = loadAll(VALIDATION);

        Set<Object> koKeys = bundles.get("ko").keySet();
        for (String lang : LANGS) {
            assertThat(bundles.get(lang).keySet())
                    .as("ValidationMessages_%s 키 집합이 ko와 다릅니다", lang)
                    .isEqualTo(koKeys);
        }
        for (String lang : LANGS) {
            for (Object key : bundles.get(lang).keySet()) {
                assertThat(((String) bundles.get(lang).get(key)).isBlank())
                        .as("ValidationMessages_%s의 %s 값이 비었습니다", lang, key).isFalse();
            }
        }
        for (Object key : koKeys) {
            for (String lang : LANGS) {
                assertThat(placeholders((String) bundles.get(lang).get(key)))
                        .as("ValidationMessages_%s의 %s placeholder 집합이 ko와 다릅니다", lang, key)
                        .isEqualTo(placeholders((String) bundles.get("ko").get(key)));
            }
        }
    }

    private static Map<String, Properties> loadAll(String pattern) {
        Map<String, Properties> bundles = new HashMap<>();
        for (String lang : LANGS) {
            String path = pattern.formatted(lang);
            Properties properties = new Properties();
            try (InputStream stream = MessageParityTest.class.getClassLoader().getResourceAsStream(path)) {
                assertThat(stream).as("%s가 클래스패스에 없습니다", path).isNotNull();
                properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(path + " 읽기 실패", e);
            }
            bundles.put(lang, properties);
        }
        return bundles;
    }

    private static Set<String> placeholders(String message) {
        Matcher matcher = PLACEHOLDER.matcher(message);
        Set<String> tokens = new TreeSet<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }
}
