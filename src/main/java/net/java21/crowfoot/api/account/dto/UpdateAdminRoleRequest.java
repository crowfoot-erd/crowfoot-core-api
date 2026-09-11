package net.java21.crowfoot.api.account.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 역할 코드 수정 요청 (08-core/05-account.md Section 2.6) — 표시명 변경만 허용.
 * level을 포함하면 서버가 400으로 거부한다(배포 수반 행위).
 */
public record UpdateAdminRoleRequest(
        @NotBlank String code,
        @NotBlank String displayName,
        Integer level
) {
}
