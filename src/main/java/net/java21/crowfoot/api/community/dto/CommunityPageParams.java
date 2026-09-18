package net.java21.crowfoot.api.community.dto;

/**
 * 커뮤니티 페이징 파라미터 정규화 (08-core/08-community.md Section 3 — 공통 페이징 준수).
 * page는 1부터, size는 1~100(기본 20) — 벗어난 값은 경계로 조정한다(400 대신).
 */
public record CommunityPageParams(int page, int size) {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    public static CommunityPageParams of(Integer page, Integer size) {
        int resolvedPage = (page == null || page < 1) ? 1 : page;
        int resolvedSize = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new CommunityPageParams(resolvedPage, resolvedSize);
    }

    public long offset() {
        return (long) (page - 1) * size;
    }
}
