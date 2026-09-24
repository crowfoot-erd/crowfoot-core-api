package net.java21.crowfoot.api.term.dto;

/**
 * 용어 사전 페이징 파라미터 정규화 (공통 페이징 준수 — 01-architecture/api-design.md Section 6.1).
 * page는 1부터, size는 1~100,000(기본 20) — 벗어난 값은 경계로 조정한다(400 대신).
 * 상한이 커뮤니티(100)보다 큰 이유: 시스템 사전은 대량(수만 토큰)으로 운영되며 논리명 추론이
 * 전체 사전을 한 번의 요청으로 내려받는다(size 100 순차 로딩이면 수백 요청이 된다).
 */
public record TermPageParams(int page, int size) {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100_000;

    public static TermPageParams of(Integer page, Integer size) {
        int resolvedPage = (page == null || page < 1) ? 1 : page;
        int resolvedSize = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new TermPageParams(resolvedPage, resolvedSize);
    }

    public long offset() {
        return (long) (page - 1) * size;
    }
}
