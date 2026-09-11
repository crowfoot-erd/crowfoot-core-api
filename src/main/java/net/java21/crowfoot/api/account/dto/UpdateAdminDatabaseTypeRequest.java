package net.java21.crowfoot.api.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 데이터베이스 종류 수정 요청 — 표시명·활성만(null 필드는 변경 없음).
 */
public record UpdateAdminDatabaseTypeRequest(
        @NotBlank @Size(max = 50) String code,
        @Size(max = 100) String displayName,
        Boolean isActive
) {
}
