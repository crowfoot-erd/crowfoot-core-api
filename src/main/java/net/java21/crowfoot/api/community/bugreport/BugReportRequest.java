package net.java21.crowfoot.api.community.bugreport;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * MCP 버그 신고 요청 (08-core/08-community.md Section 3.12 — v1.34).
 *
 * @param title      제목(200자 이하) — 게시글 제목 앞에 "[MCP 버그]"가 붙는다
 * @param content    Markdown 본문(500,000자 이하)
 * @param documentId 관련 문서 ID(선택) — 본문 끝 신고 정보에 적는다
 * @param tool       문제가 난 MCP 도구 이름(선택)
 */
public record BugReportRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 500_000) String content,
        @Pattern(regexp = "\\d{1,19}") String documentId,
        @Size(max = 100) String tool) {
}
