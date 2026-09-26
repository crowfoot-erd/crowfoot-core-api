package net.java21.crowfoot.api.internal.dto;

/**
 * 협업 서버용 문서 멤버십 조회 응답 (08-core internal — 05-editor/03-collaboration.md Section 2.1).
 * role은 유효 역할 코드(OWNER/EDITOR/COMMENTER/VIEWER), 비멤버는 "NONE" —
 * 인가 판정(거부)은 호출부(crowfoot-collab)가 이 값으로 수행한다.
 */
public record CollabMembershipResponse(String workspaceId, String role) {

    public static final String ROLE_NONE = "NONE";

    public static CollabMembershipResponse of(long workspaceId, String role) {
        return new CollabMembershipResponse(Long.toString(workspaceId), role);
    }
}
