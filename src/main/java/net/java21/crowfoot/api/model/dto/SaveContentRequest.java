package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 문서 본체 저장 요청 (08-core/02-model.md Section 1.5) — 낙관적 잠금.
 * baseVersion은 클라이언트가 읽은 버전, content는 Canonical 문서 JSON 직렬화 전문(UTF-8 5MB 상한).
 * changeSummary는 웹 에디터가 저장 직전 계산한 변경 요약 JSON(자동 메모, 1.11) — 선택 필드로
 * 서버는 내용을 해석하지 않고 64KB·JSON 구문만 가드해 그대로 스냅샷에 보관한다.
 */
public record SaveContentRequest(
        @NotNull @PositiveOrZero Integer baseVersion,
        @NotBlank String content,
        String changeSummary
) {

    /** changeSummary 생략 저장 — 요약 없이 스냅샷만 남는다(미전송 클라이언트·명시적 생략 공통) */
    public SaveContentRequest(Integer baseVersion, String content) {
        this(baseVersion, content, null);
    }
}
