package net.java21.crowfoot.api.domaintype.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 도메인 타입 만들기·고치기 요청 (08-core/16-domain-type.md Section 3.2·3.3).
 * dataType은 공용 논리 타입 코드다 — 서버는 타입 카탈로그를 갖지 않으므로 형식만 본다.
 * baseVersion은 고칠 때만 쓴다(만들 때는 무시). nullable을 생략하면 true다.
 */
public record DomainTypeRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Pattern(regexp = "[A-Z][A-Z_]{0,29}") String dataType,
        @Min(0) Integer length,
        @Min(0) Integer precision,
        @Min(0) Integer scale,
        Boolean nullable,
        @Size(max = 255) String defaultValue,
        @Size(max = 500) String description,
        Integer baseVersion
) {
}
