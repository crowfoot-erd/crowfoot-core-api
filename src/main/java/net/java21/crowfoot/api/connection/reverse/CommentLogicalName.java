package net.java21.crowfoot.api.connection.reverse;

/**
 * DB 코멘트 → 논리명 (05-editor/01-core.md §3.3 — v1.34).
 *
 * <p>DB COMMENT는 논리명의 원천이다. 짧은 코멘트는 그대로 논리명이 된다. 설명이 뭉쳐 들어온 긴
 * 코멘트는 첫 구절을 논리명으로, 나머지를 설명으로 나눠 {@code 논리명-----설명} 관례로 적는다 —
 * 화면은 논리명만 이름으로 보여 주고 설명은 정보 다이얼로그에서 보여 준다.
 * 이미 구분자가 있는 코멘트(Crowfoot이 낸 DDL의 왕복)는 그대로 둔다.
 */
public final class CommentLogicalName {

    /** 구분자 — 논리명과 설명을 한 문자열로 합칠 때 쓴다 */
    public static final String SEPARATOR = "-----";

    /** 이 길이 이하의 코멘트는 나누지 않는다 */
    static final int SPLIT_THRESHOLD = 20;

    /** 첫 구절을 끝내는 문자 — 쉼표·괄호·마침표·쌍점·쌍반점 */
    private static final String BREAKS = ",(（.:;，。：；";

    private CommentLogicalName() {
    }

    /** 코멘트를 논리명 필드 값으로. null이면 null */
    public static String of(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.strip();
        if (trimmed.length() <= SPLIT_THRESHOLD || trimmed.contains(SEPARATOR)) {
            return comment;
        }
        int cut = -1;
        for (int i = 1; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (BREAKS.indexOf(c) >= 0) {
                // "3.5"·"v1.2" 같은 숫자 사이 마침표는 구절 끝이 아니다
                if ((c == '.' || c == ',') && i + 1 < trimmed.length()
                        && Character.isDigit(trimmed.charAt(i - 1)) && Character.isDigit(trimmed.charAt(i + 1))) {
                    continue;
                }
                cut = i;
                break;
            }
        }
        if (cut <= 0) {
            return comment;
        }
        String name = trimmed.substring(0, cut).strip();
        char breakChar = trimmed.charAt(cut);
        // 괄호는 설명에 남긴다("(키 버전+IV…)"), 나머지 구분 문자는 버린다
        String description = (breakChar == '(' || breakChar == '（')
                ? trimmed.substring(cut).strip()
                : trimmed.substring(cut + 1).strip();
        if (name.isEmpty() || description.isEmpty()) {
            return comment;
        }
        return name + SEPARATOR + description;
    }
}
