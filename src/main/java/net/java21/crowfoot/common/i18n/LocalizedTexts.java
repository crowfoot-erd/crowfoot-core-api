package net.java21.crowfoot.common.i18n;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 언어→텍스트 JSONB 맵 공용 유틸 (01-architecture/api-design.md · 08-core/08-community.md §2.1) —
 * 시스템 사전 labels와 같은 JSON 문자열 컬럼(title_i18n 등)의 직렬화·해석·키 정제를 맡는다.
 *
 * <p>언어 키는 ko/en/ja/zh 4개만 의미 있고 부분 맵을 허용한다. 해석 폴백 체인은
 * 요청 언어 → en → ko → 맵 첫값 — 에디터 용어 라벨(resolveLabel)과 같은 규칙이다.
 */
public final class LocalizedTexts {

    /** 지원 언어 — 응답 availableLangs 표기 순서이기도 하다 */
    public static final List<String> SUPPORTED_LANGS = List.of("ko", "en", "ja", "zh");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private LocalizedTexts() {
    }

    /** JSON 문자열 → 삽입 순 맵. null·빈 값·깨진 JSON은 빈 맵(폴백이 첫값으로 이어진다) */
    public static LinkedHashMap<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, String> raw = MAPPER.readValue(json, MAP_TYPE);
            LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
            for (String lang : SUPPORTED_LANGS) {
                String value = raw.get(lang);
                if (value != null && !value.isBlank()) {
                    sanitized.put(lang, value);
                }
            }
            return sanitized;
        } catch (JacksonException e) {
            return new LinkedHashMap<>();
        }
    }

    /** 맵 → JSON 문자열. 비었으면 null이 아니라 "{}"(NOT NULL 컬럼) */
    public static String toJson(Map<String, String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? Map.of() : values);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    /** 해석 — 폴백 체인: lang → en → ko → 맵 첫값. 값이 전혀 없으면 빈 문자열 */
    public static String resolve(String json, String lang) {
        return resolve(fromJson(json), lang);
    }

    public static String resolve(Map<String, String> values, String lang) {
        if (values.isEmpty()) {
            return "";
        }
        String direct = values.get(lang);
        if (direct != null) {
            return direct;
        }
        String en = values.get("en");
        if (en != null) {
            return en;
        }
        String ko = values.get("ko");
        if (ko != null) {
            return ko;
        }
        return values.values().iterator().next();
    }

    /** 실제 존재하는 언어 키 — 지원 순서(ko,en,ja,zh)로 */
    public static List<String> availableLangs(String json) {
        LinkedHashMap<String, String> values = fromJson(json);
        List<String> langs = new ArrayList<>();
        for (String lang : SUPPORTED_LANGS) {
            if (values.containsKey(lang)) {
                langs.add(lang);
            }
        }
        return langs;
    }

    /** 요청 맵 정제 — 지원 키만, 공백 값 제거. 빈 맵이면 검증 단계(서비스)에서 거부한다 */
    public static LinkedHashMap<String, String> sanitize(Map<String, String> raw) {
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        if (raw != null) {
            for (String lang : SUPPORTED_LANGS) {
                String value = raw.get(lang);
                if (value != null && !value.isBlank()) {
                    sanitized.put(lang, value);
                }
            }
        }
        return sanitized;
    }
}
