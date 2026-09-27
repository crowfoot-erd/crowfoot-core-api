package net.java21.crowfoot.api.auth;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** X-USER-ID 검증 필터 테스트 — 공개 경로는 헤더 없이도 통과하고, 공유 경로(/core/shares)는
 * 3계층으로 판정한다(선택 인증 댓글·회원전용 반응 토글 — 08-core/02-model.md §1.10.6·1.10.7). */
@ExtendWith(MockitoExtension.class)
class XUserIdFilterTest {

    @Mock
    private FilterChain filterChain;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks
    private XUserIdFilter filter;

    @Test
    @DisplayName("/core/shares/{token}은 X-USER-ID 없이도 체인을 통과한다 — 토큰이 자격")
    void sharesPathSkipsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/core/shares/tok123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("댓글 경로(GET·POST .../comments)는 선택 인증 — 헤더 없어도 통과(비회원), 있으면 신원을 얹는다")
    void commentsPathIsOptionallyAuthenticated() throws Exception {
        MockHttpServletRequest anonymous =
                new MockHttpServletRequest("POST", "/core/shares/tok123/comments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(anonymous, response, filterChain);
        verify(filterChain).doFilter(anonymous, response); // 비회원 통과
        assertThat(CurrentUserHolder.getOrNull()).isNull();

        MockHttpServletRequest member =
                new MockHttpServletRequest("POST", "/core/shares/tok123/comments");
        member.addHeader("X-USER-ID", "8");
        MockHttpServletResponse memberResponse = new MockHttpServletResponse();
        filter.doFilter(member, memberResponse, filterChain);
        verify(filterChain).doFilter(member, memberResponse);
        assertThat(CurrentUserHolder.getOrNull()).isNull(); // finally 에서 clear 된다
    }

    @Test
    @DisplayName("반응 토글(POST .../reactions)은 회원전용 — X-USER-ID 없으면 401, 있으면 통과")
    void reactionsPathRequiresMember() throws Exception {
        MockHttpServletRequest anonymous =
                new MockHttpServletRequest("POST", "/core/shares/tok123/reactions");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(anonymous, rejected, filterChain);
        verify(filterChain, never()).doFilter(anonymous, rejected);
        assertThat(rejected.getStatus()).isEqualTo(401);

        MockHttpServletRequest member =
                new MockHttpServletRequest("POST", "/core/shares/tok123/reactions");
        member.addHeader("X-USER-ID", "7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(member, response, filterChain);
        verify(filterChain).doFilter(member, response);
    }

    @Test
    @DisplayName("릴리스 노트 공개 경로(/core/community/release-notes/**)는 X-USER-ID 없이도 체인을 통과한다")
    void releaseNotesPathSkipsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/core/community/release-notes/9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("템플릿 공개 목록(/core/templates)은 X-USER-ID 없이도 체인을 통과한다")
    void templatesPathSkipsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/core/templates");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("접속 비콘(/core/metrics/visit)은 X-USER-ID 없이도 체인을 통과한다 — 무인증 수집")
    void metricsBeaconPathSkipsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/core/metrics/visit");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("관리자 통계(/core/admin/metrics/**)는 X-USER-ID 없으면 401 — 공개 prefix(/core/metrics)가 넘지 않는지 감시")
    void adminMetricsPathRequiresUserId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/core/admin/metrics/summary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("커뮤니티 인증 경로(/core/community/posts/**)는 X-USER-ID 없으면 401로 거부한다 — 공개 prefix가 넘지 않는지 감시")
    void communityPostsPathRequiresUserId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/core/community/posts/recent");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("관리 경로(/core/workspaces/**)는 X-USER-ID 없으면 401로 거부한다 — 복제 경로도 포함")
    void managementPathRequiresUserId() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/core/workspaces/77/models/from-template");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("관리 경로에 X-USER-ID가 있으면 체인을 통과한다")
    void managementPathPassesWithUserId() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/core/workspaces/77/models/501/shares");
        request.addHeader("X-USER-ID", "7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
