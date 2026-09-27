package net.java21.crowfoot.api.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 공유 링크 응답(관리용 — 발급자에게만 노출) — 토큰으로 공개 주소(/share/{token})를 만든다.
 * startsAt·endsAt은 null 가능(각각 즉시·무제한) — null 필드는 응답에서 생략한다.
 * 카운터 3종(조회·반응·댓글)은 공유 다이얼로그의 링크 행 표기 원료(1.10.2) — 원자 갱신되는 표시값이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelShareResponse(
        String shareId,
        String shareToken,
        Instant startsAt,
        Instant endsAt,
        Instant createdAt,
        long viewCount,
        long reactionCount,
        long commentCount
) {
}
