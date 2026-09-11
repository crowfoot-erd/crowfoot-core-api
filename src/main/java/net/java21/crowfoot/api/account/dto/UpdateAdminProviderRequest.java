package net.java21.crowfoot.api.account.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 제공자 코드 수정 요청 (08-core/05-account.md Section 2.5) — 표시명·활성 토글.
 * 둘 다 생략하면 변경 없음(200).
 */
public record UpdateAdminProviderRequest(
        @NotBlank String code,
        String displayName,
        Boolean isActive
) {
}
