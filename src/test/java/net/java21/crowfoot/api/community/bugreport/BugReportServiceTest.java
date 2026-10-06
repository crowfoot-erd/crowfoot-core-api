package net.java21.crowfoot.api.community.bugreport;

import net.java21.crowfoot.api.community.dto.CommunityPostDetailResponse;
import net.java21.crowfoot.api.community.dto.CreateCommunityPostRequest;
import net.java21.crowfoot.api.community.service.CommunityPostService;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MCP 버그 신고 (08-core/08-community.md Section 3.12) */
class BugReportServiceTest {

    private final RoleChecker roleChecker = mock(RoleChecker.class);
    private final CommunityPostService communityPostService = mock(CommunityPostService.class);
    private final BugReportService service = new BugReportService(roleChecker, communityPostService);

    @Test
    @DisplayName("요청자 이름으로 FEEDBACK 게시판에 [MCP 버그] 제목과 신고 정보가 붙은 Markdown 글을 쓴다")
    void reportsAsCaller() {
        when(communityPostService.create(eq(7L), any())).thenReturn(new CommunityPostDetailResponse(
                "91", "FEEDBACK", "[MCP 버그] 배포 SQL 문법 오류", List.of("ko"), "본문", null, Instant.now(), Instant.now()));

        BugReportResponse response = service.report(7L, 53L, new BugReportRequest(
                "배포 SQL 문법 오류", "## 요약\nDEFAULT USER", "644", "plan_deployment"));

        ArgumentCaptor<CreateCommunityPostRequest> captor = ArgumentCaptor.forClass(CreateCommunityPostRequest.class);
        verify(roleChecker).requireMember(7L, 53L);
        verify(communityPostService).create(eq(7L), captor.capture());
        CreateCommunityPostRequest request = captor.getValue();
        assertThat(request.board()).isEqualTo("FEEDBACK");
        assertThat(request.title().values().toString()).contains("[MCP 버그] 배포 SQL 문법 오류");
        assertThat(request.content().values().toString()).contains("## 요약", "- 신고 경로: MCP", "- 워크스페이스: 53", "- 문서: 644", "`plan_deployment`");
        assertThat(response.postId()).isEqualTo("91");
        assertThat(response.path()).isEqualTo("/community/posts/91");
    }

    @Test
    @DisplayName("워크스페이스 멤버가 아니면 글을 쓰지 않는다")
    void requiresMembership() {
        when(roleChecker.requireMember(anyLong(), anyLong())).thenThrow(new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND));

        assertThatThrownBy(() -> service.report(7L, 53L, new BugReportRequest("t", "c", null, null)))
                .isInstanceOf(BusinessException.class);
        verify(communityPostService, never()).create(anyLong(), any());
    }
}
