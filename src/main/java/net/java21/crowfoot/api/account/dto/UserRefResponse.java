package net.java21.crowfoot.api.account.dto;

/**
 * 사용자 참조 Object — { userId, name } (api-design Section 5.5 — ***By 표시용,
 * 프론트가 별도 조회 없이 이름을 표시하도록 함께 내려준다).
 */
public record UserRefResponse(String userId, String name) {
}
