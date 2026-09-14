package net.java21.crowfoot.api.managed.dto;

/**
 * 발급 한도 요약 (08-core/07-managed-database.md Section 3.5) — 서비스 고정
 * 한도(limit)·워크스페이스 내 요청자 발급 수(used, 모든 인스턴스 합산)·잔여(remaining).
 * 비활성 인스턴스도 목록에 남는다 — 잔여가 있어도 신규 발급은 막힌다.
 */
public record ManagedLimitSummary(
        String instanceId,
        String displayName,
        boolean isActive,
        int limit,
        long used,
        long remaining
) {
}
