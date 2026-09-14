package net.java21.crowfoot.api.managed.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.crowfoot.common.ResponseHeader;

import java.util.List;

/**
 * 매니지드 발급 목록 응답 (08-core/07-managed-database.md Section 3.5) —
 * 목록 본문에 이어 요청자 기준 인스턴스별 한도 요약(limitSummary)을 얹는다.
 * 필드 순서는 ListApiResponse 관례(header → responses → totalCount)에 따른다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManagedDatabaseListResponse(
        ResponseHeader header,
        List<ManagedDatabaseResponse> responses,
        long totalCount,
        List<ManagedLimitSummary> limitSummary
) {

    public static ManagedDatabaseListResponse of(List<ManagedDatabaseResponse> responses,
                                                 List<ManagedLimitSummary> limitSummary) {
        return new ManagedDatabaseListResponse(ResponseHeader.success(),
                responses == null ? List.of() : responses,
                responses == null ? 0 : responses.size(),
                limitSummary == null ? List.of() : limitSummary);
    }
}
