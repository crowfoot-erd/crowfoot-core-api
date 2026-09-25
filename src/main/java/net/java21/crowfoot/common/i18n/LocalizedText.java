package net.java21.crowfoot.common.i18n;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 다국어 텍스트 요청 값 (08-core/08-community.md §2.1 쓰기 다형) — JSON에서 문자열 또는
 * 언어 객체를 받는다: {@code "title": "제목"} 또는 {@code "title": {"ko":"제목","en":"Title"}}.
 *
 * <p>문자열은 {@code {ko: 값}}으로 정규화한다(구버전 클라이언트·FEEDBACK 일반글 호환 —
 * 병합 저장이라 다른 언어는 유지된다). 객체는 지원 언어(ko/en/ja/zh)의 공백 아닌 값만 남긴다.
 * 각 값의 길이 상한은 이 타입이 아니라 서비스가 필드별로 검증한다.
 */
public final class LocalizedText {

    private final LinkedHashMap<String, String> values;

    private LocalizedText(LinkedHashMap<String, String> values) {
        this.values = values;
    }

    /** Jackson 역직렬화 진입점 — 문자열·맵 외 타입(숫자 등)은 IllegalArgumentException(400) */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static LocalizedText of(Object raw) {
        if (raw instanceof String text) {
            LinkedHashMap<String, String> single = new LinkedHashMap<>();
            if (!text.isBlank()) {
                single.put("ko", text);
            }
            return new LocalizedText(single);
        }
        if (raw instanceof Map<?, ?> map) {
            LinkedHashMap<String, String> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String lang && entry.getValue() instanceof String value) {
                    converted.put(lang, value);
                }
            }
            return new LocalizedText(LocalizedTexts.sanitize(converted));
        }
        throw new IllegalArgumentException("다국어 텍스트는 문자열 또는 언어 객체(ko/en/ja/zh)여야 합니다");
    }

    /** 정제된 값 맵(불변 아님 — 병합에 사용) */
    public Map<String, String> values() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** 저장용 JSON 문자열 */
    @JsonValue
    public String toJson() {
        return LocalizedTexts.toJson(values);
    }

    /** 값 동등성 — 요청 DTO(record) 비교·테스트 스텁 매칭이 언어 맵 기준으로 성립하도록 */
    @Override
    public boolean equals(Object other) {
        return other instanceof LocalizedText text && values.equals(text.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }
}
