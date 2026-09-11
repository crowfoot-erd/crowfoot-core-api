package net.java21.crowfoot.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 성공 — 목록 응답. (01-architecture/api-design.md Section 5.2)
 *
 * <p>모든 목록은 {@code totalCount}를 포함하고, 필드 순서는
 * header → 페이징 메타(page/size/totalPages) → responses → totalCount 로 통일한다.
 * 페이징하지 않는 목록(팀 목록·멤버 목록·후보 검색 등)은 페이징 메타를 생략한다.
 *
 * @param header      항상 존재
 * @param page        1부터 시작 (페이징 목록만)
 * @param size        페이지 크기 (페이징 목록만)
 * @param totalPages  전체 페이지 수 (페이징 목록만)
 * @param responses   항목 목록 — 값이 없어도 null이 아닌 빈 배열
 * @param totalCount  전체 항목 수
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListApiResponse<T>(
        ResponseHeader header,
        Integer page,
        Integer size,
        Integer totalPages,
        List<T> responses,
        long totalCount
) {

    /** 페이징 없는 목록 */
    public static <T> ListApiResponse<T> of(List<T> responses) {
        return new ListApiResponse<>(ResponseHeader.success(), null, null, null,
                responses == null ? List.of() : responses,
                responses == null ? 0 : responses.size());
    }

    /** 페이징 목록 (page 파라미터는 1부터 시작) */
    public static <T> ListApiResponse<T> paged(List<T> responses, int page, int size, long totalCount) {
        int totalPages = size == 0 ? 0 : (int) ((totalCount + size - 1) / size);
        return new ListApiResponse<>(ResponseHeader.success(), page, size, totalPages,
                responses == null ? List.of() : responses, totalCount);
    }
}
