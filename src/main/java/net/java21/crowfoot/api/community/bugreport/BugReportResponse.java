package net.java21.crowfoot.api.community.bugreport;

/**
 * MCP 버그 신고 결과 — 만들어진 "제안 및 신고" 게시글.
 *
 * @param postId 게시글 ID
 * @param title  저장된 제목
 * @param path   웹에서 여는 경로(/community/posts/{postId})
 */
public record BugReportResponse(String postId, String title, String path) {
}
