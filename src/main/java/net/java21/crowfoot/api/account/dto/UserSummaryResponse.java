package net.java21.crowfoot.api.account.dto;

/**
 * 사용자 요약 — 멤버십 부여 대상(granteeType=USER) 표시. { userId, name, email }.
 */
public record UserSummaryResponse(String userId, String name, String email) {
}
