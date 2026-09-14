package net.java21.crowfoot.api.managed.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 매니지드 인스턴스 변경 요청 (08-core/07-managed-database.md Section 3.3) —
 * PATCH 의미론: null 필드는 변경 없음. password는 평문으로 받아 왔을 때만 재암호화한다.
 * 자격(host·port·databaseName·username·password)을 바꾸면 이후 발급부터 새 자격이 쓰인다.
 */
public record UpdateManagedInstanceRequest(
        @Size(min = 1, max = 100) String displayName,
        @Size(min = 1, max = 255) String host,
        @Min(1) @Max(65535) Integer port,
        @Size(min = 1, max = 100) String databaseName,
        @Size(min = 1, max = 100) String username,
        @Size(min = 1, max = 255) String password,
        Boolean isActive
) {
}
