package net.java21.crowfoot.api.community.bugreport;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.community.dto.CommunityPostDetailResponse;
import net.java21.crowfoot.api.community.dto.CreateCommunityPostRequest;
import net.java21.crowfoot.api.community.service.CommunityPostService;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.i18n.LocalizedText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 버그 신고 (08-core/08-community.md Section 3.12 — v1.34).
 *
 * <p>MCP를 쓰다 겪은 문제를 Claude가 Markdown으로 정리해 "제안 및 신고"(FEEDBACK) 게시판에 올린다.
 * 작성자는 요청을 보낸 사용자 — MCP 요청이면 워크스페이스 액세스 토큰을 발급한 사용자다.
 * 토큰은 커뮤니티 경로를 부를 수 없으므로(TokenScope) 워크스페이스 아래 경로로 받고, 워크스페이스 멤버만 쓸 수 있다.
 * 게시글 생성·감사는 일반 글쓰기와 같은 서비스를 거친다.
 */
@Service
@RequiredArgsConstructor
public class BugReportService {

    static final String TITLE_PREFIX = "[MCP 버그] ";

    private final RoleChecker roleChecker;
    private final CommunityPostService communityPostService;

    @Transactional
    public BugReportResponse report(long userId, long workspaceId, BugReportRequest request) {
        roleChecker.requireMember(userId, workspaceId);
        String title = (TITLE_PREFIX + request.title().strip());
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        StringBuilder content = new StringBuilder(request.content().strip())
                .append("\n\n---\n\n")
                .append("- 신고 경로: MCP\n")
                .append("- 워크스페이스: ").append(workspaceId).append('\n');
        if (request.documentId() != null) {
            content.append("- 문서: ").append(request.documentId()).append('\n');
        }
        if (request.tool() != null && !request.tool().isBlank()) {
            content.append("- 도구: `").append(request.tool().strip().replace("`", "")).append("`\n");
        }
        CommunityPostDetailResponse post = communityPostService.create(userId, new CreateCommunityPostRequest(
                "FEEDBACK", LocalizedText.of(title), LocalizedText.of(content.toString())));
        return new BugReportResponse(post.postId(), title, "/community/posts/" + post.postId());
    }
}
