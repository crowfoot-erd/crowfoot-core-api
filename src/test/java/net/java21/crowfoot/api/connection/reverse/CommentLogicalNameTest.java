package net.java21.crowfoot.api.connection.reverse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 코멘트 → 논리명 (v1.34 — 긴 코멘트는 첫 구절이 논리명, 나머지는 "-----" 뒤 설명) */
class CommentLogicalNameTest {

    @Test
    @DisplayName("짧은 코멘트·구분 문자가 없는 코멘트·이미 구분자가 있는 코멘트는 그대로")
    void keeps() {
        assertThat(CommentLogicalName.of("수정 시각")).isEqualTo("수정 시각");
        assertThat(CommentLogicalName.of("항목 PROFILE/CATEGORIES/RECENT_POSTS/RECENT_COMMENTS"))
                .isEqualTo("항목 PROFILE/CATEGORIES/RECENT_POSTS/RECENT_COMMENTS");
        assertThat(CommentLogicalName.of("이메일-----로그인 아이디, 소문자")).isEqualTo("이메일-----로그인 아이디, 소문자");
        assertThat(CommentLogicalName.of(null)).isNull();
    }

    @Test
    @DisplayName("긴 코멘트 — 괄호는 설명에 남기고, 쉼표는 버린다. 숫자 사이 마침표는 구절 끝이 아니다")
    void splits() {
        assertThat(CommentLogicalName.of("이메일 AES-256-GCM 암호문(키 버전+IV+암호문+태그), 소문자 정규화 후 암호화"))
                .isEqualTo("이메일 AES-256-GCM 암호문-----(키 버전+IV+암호문+태그), 소문자 정규화 후 암호화");
        assertThat(CommentLogicalName.of("이메일 주소(로그인 아이디), 소문자로 정규화해 저장한다"))
                .isEqualTo("이메일 주소-----(로그인 아이디), 소문자로 정규화해 저장한다");
        assertThat(CommentLogicalName.of("평점 3.5 기준 노출 여부, 관리자만 바꾼다"))
                .isEqualTo("평점 3.5 기준 노출 여부-----관리자만 바꾼다");
    }
}
