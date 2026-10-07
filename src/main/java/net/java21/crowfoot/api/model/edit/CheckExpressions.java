package net.java21.crowfoot.api.model.edit;

/**
 * CHECK 식 원문 읽기 — 식이 컬럼을 쓰는지 본다(17-model-edit.md — 컬럼을 지우면 그 컬럼을 쓰는 CHECK도 지운다, 신고 45).
 *
 * <p>문자열 리터럴 안은 보지 않는다. 따옴표 없는 식별자와 따옴표(큰따옴표·백틱·대괄호) 식별자를 대소문자 없이 비교한다.
 * 웹 에디터의 keys.ts checkReferencesColumn과 같은 규칙이다.
 */
final class CheckExpressions {

    private CheckExpressions() {
    }

    static boolean references(String expression, String columnName) {
        if (expression == null || columnName == null || columnName.isEmpty()) {
            return false;
        }
        int i = 0;
        int length = expression.length();
        while (i < length) {
            char ch = expression.charAt(i);
            if (ch == '\'') {
                i = skipQuoted(expression, i, '\'');
                continue;
            }
            if (ch == '"' || ch == '`' || ch == '[') {
                char close = ch == '[' ? ']' : ch;
                int end = skipQuoted(expression, i, close);
                String name = expression.substring(i + 1, Math.max(i + 1, end - 1)).replace(String.valueOf(close) + close, String.valueOf(close));
                if (name.equalsIgnoreCase(columnName)) {
                    return true;
                }
                i = end;
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int start = i;
                while (i < length && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '_' || expression.charAt(i) == '$')) {
                    i++;
                }
                if (expression.substring(start, i).equalsIgnoreCase(columnName)) {
                    return true;
                }
                continue;
            }
            if (Character.isDigit(ch)) {
                // 숫자 뒤에 붙은 글자(1e5·0x1F)는 식별자가 아니다
                while (i < length && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '.')) {
                    i++;
                }
                continue;
            }
            i++;
        }
        return false;
    }

    /** 따옴표 하나가 연 구간의 끝 다음 위치 — 닫는 문자를 두 번 쓰면 이스케이프다 */
    private static int skipQuoted(String text, int open, char close) {
        int i = open + 1;
        while (i < text.length()) {
            if (text.charAt(i) == close) {
                if (i + 1 < text.length() && text.charAt(i + 1) == close) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }
}
