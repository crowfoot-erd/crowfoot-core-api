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

/** X-USER-ID 검증 필터 테스트 — 공개 경로(/core/providers·/core/shares·/core/community/release-notes)는 헤더 없이도 통과한다. */
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
    @DisplayName("릴리스 노트 공개 경로(/core/community/release-notes/**)는 X-USER-ID 없이도 체인을 통과한다")
    void releaseNotesPathSkipsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/core/community/release-notes/9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
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
    @DisplayName("관리 경로(/core/workspaces/**)는 X-USER-ID 없으면 401로 거부한다")
    void managementPathRequiresUserId() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/core/workspaces/77/models/501/shares");
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
