package net.java21.crowfoot.api.managed.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 매니지드 인스턴스 등록 요청 (08-core/07-managed-database.md Section 3.2) —
 * 등록은 곧 검증이다: 제출된 자격으로 SELECT 1을 실행해 실패하면 등록이 거부된다.
 * isActive는 생략 시 true. databaseName은 DBMS별로 갈린다 —
 * PostgreSQL(접속 단위가 database)은 필수, MySQL(발급이 database 생성)은 생략이 정상이며
 * 필수 여부는 서비스가 프로비저너 전략에 위임해 판정한다.
 */
public record CreateManagedInstanceRequest(
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Size(max = 50) String dbmsType,
        @NotBlank @Size(max = 255) String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @Size(max = 100) String databaseName,
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 255) String password,
        /** 생략 시 true */
        Boolean isActive
) {
}
