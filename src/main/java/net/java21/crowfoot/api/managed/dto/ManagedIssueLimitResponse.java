package net.java21.crowfoot.api.managed.dto;

/**
 * 매니지드 발급 한도 응답 (08-core/07-managed-database.md Section 3.2.1) —
 * 관리자가 지정한 워크스페이스 내 사용자당 발급 한도(모든 인스턴스 합산 기준).
 */
public record ManagedIssueLimitResponse(int limit) {
}
