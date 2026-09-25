package net.java21.crowfoot.api.community.domain;

import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;

/**
 * 커뮤니티 게시판 종류 (08-core/08-community.md Section 2) — 2값 고정이라 코드 테이블 대신 enum.
 *
 * <p>쓰기 권한이 값마다 다르다 — RELEASE_NOTE(릴리스 노트)는 관리자 전용,
 * FEEDBACK(제안 및 신고)은 로그인 사용자 전체. 코멘트는 FEEDBACK 게시글에만 허용된다.
 */
public enum CommunityBoard {

    /** 릴리스 노트 — 버전별 게시글, 관리자만 작성·수정·삭제, 코멘트 없음(읽기 전용) */
    RELEASE_NOTE,

    /** 제안 및 신고 — 로그인 사용자 누구나 작성, 코멘트 허용 */
    FEEDBACK;

    /** 요청 파라미터 파싱 — 허용 외 값은 400(도메인 무효 값 원천 차단) */
    public static CommunityBoard fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.board.required");
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.board.unknown", value);
        }
    }
}
