package net.java21.crowfoot.api.connection.dto;

import jakarta.validation.constraints.Size;

/**
 * 리버스 엔지니어링 요청 (08-core/06-connection.md Section 3.6) — 둘 다 선택.
 * modelName을 생략하면 "{커넥션 이름} ERD"가 된다.
 */
public record ReverseEngineeringRequest(
        @Size(min = 1, max = 100) String modelName,
        @Size(max = 500) String description
) {
}
