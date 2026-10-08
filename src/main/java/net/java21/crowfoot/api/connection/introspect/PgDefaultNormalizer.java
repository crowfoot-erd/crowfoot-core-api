package net.java21.crowfoot.api.connection.introspect;

import java.util.regex.Pattern;

/**
 * PostgreSQL 기본값 표기 정리 (05-editor/04-dbms-engineering.md Section 3.2 — v1.40, 커뮤니티 신고 50).
 *
 * <p>PostgreSQL은 {@code information_schema.columns.column_default}에 캐스트를 붙여 돌려준다 —
 * {@code 'member'::text}, {@code to_char(LOCALTIMESTAMP, 'YYYY-MM-DD HH24:MI:SS'::text)}, {@code '0'::numeric}.
 * 문서는 배포 DDL에 쓴 표기(캐스트 없음, 문자열은 따옴표 없이)로 저장하므로 같은 꼴로 되돌린다.
 * <ol>
 * <li>값 전체에 붙은 맨 바깥 캐스트를 뗀다(괄호 깊이 0, 문자열 밖) — {@code '0'::numeric} → {@code '0'}
 * <li>문자열 밖의 문자열 타입 캐스트({@code ::text}, {@code ::character varying(n)}, {@code ::bpchar})를 지운다 —
 *     PostgreSQL이 함수 인자의 문자열 상수에 스스로 붙인 것이다. 다른 타입 캐스트는 뜻을 바꿀 수 있어 둔다
 * <li>값 전체가 문자열 상수 하나면 따옴표를 벗긴다({@code ''} 이스케이프를 푼다). 빈 문자열은 {@code ''}로 둔다
 * </ol>
 * 종전 정규식({@code ^(.*?)::[a-zA-Z][a-zA-Z0-9_ ()]*$})은 첫 {@code ::}부터 끝까지를 지워 함수 인자 안 캐스트를
 * 지울 때 바깥 닫는 괄호까지 지웠다.
 */
final class PgDefaultNormalizer {

    /** 캐스트 대상 타입 이름 — {@code text}, {@code character varying(20)}, {@code timestamp(6) without time zone}, {@code text[]} */
    private static final Pattern TYPE_NAME = Pattern.compile(
            "[a-zA-Z_][a-zA-Z0-9_]*(?:\\s+[a-zA-Z_][a-zA-Z0-9_]*)*(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?(?:\\s+[a-zA-Z_][a-zA-Z0-9_]*)*(?:\\[\\])*");

    /** 문자열 밖에서 지우는 문자열 타입 캐스트 */
    private static final Pattern TEXT_CAST = Pattern.compile(
            "::(?:text|bpchar|varchar|character varying|character|char)(?:\\s*\\(\\s*\\d+\\s*\\))?(?:\\[\\])?(?![a-zA-Z0-9_])");

    private PgDefaultNormalizer() {
    }

    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        String previous;
        do {
            previous = value;
            value = stripTopLevelCast(value);
        } while (!value.equals(previous));
        value = removeTextCasts(value);
        if (isSingleLiteral(value) && value.length() > 2) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    /** 괄호 깊이 0·문자열 밖의 마지막 {@code ::} 뒤가 타입 이름뿐이면 그 캐스트를 뗀다 */
    private static String stripTopLevelCast(String value) {
        int depth = 0;
        boolean quoted = false;
        int lastCast = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') {
                quoted = !quoted; // '' 이스케이프는 닫고 바로 여는 것과 같다
            } else if (!quoted) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ':' && depth == 0 && i + 1 < value.length() && value.charAt(i + 1) == ':') {
                    lastCast = i;
                    i++;
                }
            }
        }
        if (lastCast <= 0) {
            return value;
        }
        String type = value.substring(lastCast + 2).trim();
        return TYPE_NAME.matcher(type).matches() ? value.substring(0, lastCast).trim() : value;
    }

    /** 문자열 상수 밖의 문자열 타입 캐스트를 지운다 */
    private static String removeTextCasts(String value) {
        StringBuilder out = new StringBuilder();
        StringBuilder outside = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') {
                if (!quoted) {
                    out.append(TEXT_CAST.matcher(outside).replaceAll(""));
                    outside.setLength(0);
                }
                quoted = !quoted;
                out.append(c);
            } else if (quoted) {
                out.append(c);
            } else {
                outside.append(c);
            }
        }
        out.append(TEXT_CAST.matcher(outside).replaceAll(""));
        return out.toString();
    }

    /** 값 전체가 문자열 상수 하나인가 — 안쪽 작은따옴표는 모두 '' 짝이다('a' || 'b' 같은 식 제외) */
    private static boolean isSingleLiteral(String value) {
        if (value.length() < 2 || !value.startsWith("'") || !value.endsWith("'")) {
            return false;
        }
        return !value.substring(1, value.length() - 1).replace("''", "").contains("'");
    }
}
