package net.java21.crowfoot.api.managed.dto;

/**
 * 매니지드 발급 요청 (08-core/07-managed-database.md Section 3.6) —
 * instanceId 생략 시 활성 인스턴스 중 첫 번째로 발급한다.
 */
public record IssueManagedDatabaseRequest(
        Long instanceId
) {
}
