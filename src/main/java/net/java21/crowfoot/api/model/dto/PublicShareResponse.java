package net.java21.crowfoot.api.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 공유 문서 공개 응답 (08-core/02-model.md Section 1.10) — 인증 없이 토큰으로만 접근.
 * 뷰어 화면에 필요한 문서 메타와 content를 함께 내려준다(읽기 전용 — 저장 API 없음).
 * startsAt·endsAt은 null 가능(각각 즉시·무제한) — null 필드는 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicShareResponse(
        String modelName,
        String description,
        String databaseType,
        int version,
        String content,
        Instant startsAt,
        Instant endsAt
) {
}
