package net.java21.crowfoot.api.domaintype.dto;

import java.time.Instant;

/** 도메인 타입 응답 (08-core/16-domain-type.md Section 3) — 목록·만들기·고치기 공통 */
public record DomainTypeResponse(
        String domainTypeId,
        String workspaceId,
        String name,
        String dataType,
        Integer length,
        Integer precision,
        Integer scale,
        boolean nullable,
        String defaultValue,
        String description,
        int version,
        Instant updatedAt
) {
}
