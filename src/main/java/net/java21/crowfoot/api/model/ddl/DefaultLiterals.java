package net.java21.crowfoot.api.model.ddl;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 컬럼 기본값의 DDL 표기 (05-editor/04-dbms-engineering.md §3.1 — v1.34).
 *
 * <p>content는 문자열 기본값을 따옴표 없이 저장한다({@code ACTIVE}). 리버스(PostgreSQL)는
 * 따옴표째({@code 'ACTIVE'}) 저장하기도 한다. 생성기는 컬럼 타입 범주로 표기를 정한다 —
 * 숫자·불리언 타입은 값 그대로, 그 밖의 타입은 작은따옴표로 감싼다. 이미 따옴표로 감싼 값,
 * 괄호 식, {@code NULL}, 함수 호출 꼴은 어느 타입이든 그대로 둔다. {@code CURRENT_TIMESTAMP}
 * 같은 시각 키워드는 날짜시간 타입에서만 그대로 둔다.
 */
public final class DefaultLiterals {

    /** 숫자 범주 — 기본값을 그대로 낸다 */
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "MEDIUMINT",
            "DECIMAL", "NUMERIC", "DEC", "FLOAT", "DOUBLE", "DOUBLE PRECISION", "REAL",
            "INT2", "INT4", "INT8", "FLOAT4", "FLOAT8", "NUMBER");

    private static final Set<String> BOOLEAN_TYPES = Set.of("BOOLEAN", "BOOL", "BIT");

    /** 날짜시간 범주 — 아래 시각 키워드를 값이 아니라 키워드로 본다 */
    private static final Set<String> DATETIME_TYPES = Set.of(
            "DATE", "TIME", "DATETIME", "TIMESTAMP", "TIMESTAMPTZ", "TIMETZ", "DATETIME2");

    /** 날짜시간 타입에서만 키워드로 보는 값. 문자 타입의 {@code CURRENT_DATE}는 문자열이다
     *  (문자 타입의 {@code USER}·{@code DEFAULT}가 키워드로 나가 DDL이 깨진 일 — v1.34 보고) */
    private static final Set<String> TIME_KEYWORDS = Set.of(
            "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
            "LOCALTIMESTAMP", "LOCALTIME", "SYSDATE", "SYSTIMESTAMP");

    /** 함수 호출 꼴 — NOW(), CURRENT_TIMESTAMP(6), gen_random_uuid(), nextval('s'::regclass) */
    private static final Pattern FUNCTION_CALL = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]*\\s*\\(.*\\)$", Pattern.DOTALL);

    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");

    /** PostgreSQL 캐스트가 붙은 문자열 — 'x'::type */
    private static final Pattern QUOTED_WITH_CAST = Pattern.compile("^'(?:[^']|'')*'::.+$", Pattern.DOTALL);

    private DefaultLiterals() {
    }

    /**
     * DDL에 실을 기본값 표기. 기본값이 없으면(null·빈 문자열) null.
     *
     * @param dialectId 방언 id — 불리언 타입이 숫자로 매핑되는 방언(oracle·mssql)은 true/false를 1/0으로 낸다
     */
    public static String render(DdlContent.Column column, String dialectId) {
        String value = column.defaultValue();
        if (value == null || value.isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        String type = typeKey(column.dataType());
        if (BOOLEAN_TYPES.contains(type)) {
            return renderBoolean(trimmed, dialectId);
        }
        if ("NULL".equalsIgnoreCase(trimmed) || isExpression(trimmed)) {
            return trimmed;
        }
        if (DATETIME_TYPES.contains(type) && TIME_KEYWORDS.contains(trimmed.toUpperCase(Locale.ROOT))) {
            return trimmed;
        }
        if (NUMERIC_TYPES.contains(type) && NUMBER.matcher(trimmed).matches()) {
            return trimmed;
        }
        return quote(value);
    }

    /** 두 기본값이 같은 값을 뜻하는가 — 빈 문자열과 null은 같고, 바깥 따옴표 유무만 다른 값도 같다 */
    public static boolean sameDefault(DdlContent.Column before, DdlContent.Column after) {
        String beforeKey = comparisonKey(before.defaultValue());
        String afterKey = comparisonKey(after.defaultValue());
        // 불리언 — MySQL은 TRUE를 1로 돌려준다. 어느 한쪽이 불리언 컬럼이면 TRUE≡1, FALSE≡0
        if (BOOLEAN_TYPES.contains(typeKey(before.dataType())) || BOOLEAN_TYPES.contains(typeKey(after.dataType()))) {
            beforeKey = booleanKey(beforeKey);
            afterKey = booleanKey(afterKey);
        }
        return Objects.equals(beforeKey, afterKey);
    }

    private static String booleanKey(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "TRUE", "1", "B'1'" -> "1";
            case "FALSE", "0", "B'0'" -> "0";
            default -> value;
        };
    }

    /** 비교 키 — 바깥 작은따옴표를 벗기고 '' 이스케이프를 푼다 */
    static String comparisonKey(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        if (isQuoted(trimmed)) {
            return trimmed.substring(1, trimmed.length() - 1).replace("''", "'");
        }
        return value;
    }

    private static String renderBoolean(String value, String dialectId) {
        String upper = value.toUpperCase(Locale.ROOT);
        boolean numeric = "oracle".equals(dialectId) || "mssql".equals(dialectId);
        if ("TRUE".equals(upper) || "'TRUE'".equals(upper)) {
            return numeric ? "1" : "TRUE";
        }
        if ("FALSE".equals(upper) || "'FALSE'".equals(upper)) {
            return numeric ? "0" : "FALSE";
        }
        return value; // 0·1·b'1'·식 — 그대로
    }

    private static boolean isExpression(String value) {
        if (isQuoted(value) || QUOTED_WITH_CAST.matcher(value).matches()) {
            return true;
        }
        if (value.startsWith("(") && value.endsWith(")")) {
            return true;
        }
        return FUNCTION_CALL.matcher(value).matches();
    }

    private static boolean isQuoted(String value) {
        if (value.length() < 2 || !value.startsWith("'") || !value.endsWith("'")) {
            return false;
        }
        // 안쪽의 작은따옴표는 모두 '' 짝이어야 한 덩어리 문자열이다('a' || 'b' 같은 식 제외)
        String inner = value.substring(1, value.length() - 1);
        return !inner.replace("''", "").contains("'");
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String typeKey(String dataType) {
        return dataType == null ? "" : dataType.trim().toUpperCase(Locale.ROOT);
    }
}
