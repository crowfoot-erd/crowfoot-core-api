package net.java21.crowfoot.api.connection.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 커넥션 등록 요청 (08-core/06-connection.md Section 3.2).
 * password는 평문으로 받아 즉시 암호화 저장한다 — 등록 시 접속을 검증하지 않는다(테스트와 분리).
 */
public record CreateConnectionRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50) String dbmsType,
        @NotBlank @Size(max = 255) String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotBlank @Size(max = 100) String databaseName,
        /** PostgreSQL 스키마(선택) — 다른 DBMS는 스키마 개념이 DB 자체라 무시된다 */
        @Size(max = 100) String schemaName,
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 255) String password
) {
}
