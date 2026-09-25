package net.java21.crowfoot.api.community.dto;

import net.java21.crowfoot.common.i18n.LocalizedText;

/**
 * 게시글 수정 요청 (08-core/08-community.md Section 3.5) — board 필드가 없어 게시판 이동은 원천 불가.
 * 작성자 본인 또는 관리자만 호출할 수 있다.
 * title·content는 다국어 텍스트(문자열 또는 언어 객체) — 값이 있는 키만 병합(부분 갱신), 문자열은 {ko:값} 병합.
 */
public record UpdateCommunityPostRequest(
        LocalizedText title,
        LocalizedText content) {
}
