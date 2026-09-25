package net.java21.crowfoot.api.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * UI 언어 설정 요청 (08-core/05-account.md Section 1.4) — 계정 단위 언어 저장.
 * 지원 언어 4개로 제한(정규식) — CHECK 제약과 같은 값 세트.
 */
public record UpdateMyLocaleRequest(
        @NotBlank
        @Pattern(regexp = "ko|en|ja|zh", message = "{validation.account.locale.range}")
        String locale
) {
}
