package net.java21.crowfoot.api.auth;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.common.ErrorResponse;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * X-USER-ID 검증 필터 (08-core/00-overview.md Section 1).
 *
 * <p>구현 경로 {@code /core/**}는 Gateway가 주입한 X-USER-ID(sub 문자열)을 요구한다 —
 * 헤더 없는 요청은 Gateway를 거치지 않은 요청이므로 401로 거부한다(공통 실패 포맷).
 * 제외 경로: {@code /internal/**}(내부망 — 인증 서버 호출), {@code /core/providers}(공개),
 * {@code /core/community/release-notes/**}(릴리스 노트 공개 조회),
 * {@code /core/templates}(템플릿 공개 목록 — 08-core/09-templates.md),
 * {@code /core/metrics/**}(접속 비콘 수집 — 무인증, 08-core/10-metrics.md Section 3.
 * 관리자 조회 {@code /core/admin/metrics/**}는 제외 대상 아니다 — X-USER-ID 필요),
 * {@code /actuator/**}(헬스체크).
 *
 * <p>공유 경로 {@code /core/shares/**}는 3계층이다 (08-core/02-model.md Section 1.10.6~1.10.8) —
 * ① 공개 조회·갤러리·공개 DDL·반응 외 댓글 경로: 헤더가 있으면 신원을 얹고(선택 인증 — 회원·비회원
 * 댓글이 같은 경로), 없으면 익명으로 통과한다. ② 반응 토글 {@code POST .../reactions}: 회원전용이라
 * 헤더를 요구한다(없으면 401 — Gateway가 이미 막지만 내부망 직접 호출 방어). ③ 그 밖의 공개 경로
 * (조회·갤러리·DDL)는 헤더 없이 통과한다.
 */
@Component
@RequiredArgsConstructor
public class XUserIdFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-USER-ID";
    /** 워크스페이스 액세스 토큰이 묶인 워크스페이스 — 이 헤더가 있으면 토큰으로 온 요청이다 */
    public static final String TOKEN_WORKSPACE_HEADER = "X-TOKEN-WORKSPACE-ID";
    public static final String ACCESS_TOKEN_HEADER = "X-ACCESS-TOKEN-ID";

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/core")                       // /core/** 외에는 인증 대상 아님
                || "/core/providers".equals(path)              // 활성 제공자 목록 — 공개
                || path.startsWith("/core/community/release-notes") // 릴리스 노트 공개 조회 — Section 3.11
                || path.startsWith("/core/templates")            // 템플릿 공개 목록 — GET만 Gateway 화이트리스트
                || path.startsWith("/core/metrics")              // 접속 비콘 수집(POST visit) — 무인증, 관리자 /core/admin/metrics는 별도
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
        // /core/shares/**는 제외하지 않는다 — doFilterInternal이 3계층(선택 인증·회원전용)으로 판정한다
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isSharePath(request) && !isMemberOnlySharePath(request)) {
            filterSharePath(request, response, filterChain);   // 공유 경로 — 선택 인증 3계층 판정
            return;
        }
        String header = request.getHeader(USER_ID_HEADER);
        if (header == null || header.isBlank()) {
            writeUnauthorized(response);
            return;
        }
        Long userId;
        try {
            userId = Long.parseLong(header.trim());
        } catch (NumberFormatException ex) {
            writeUnauthorized(response);
            return;
        }
        // 워크스페이스 액세스 토큰으로 온 요청(MCP) — 범위를 판정한다 (08-core/18-access-token.md Section 4)
        Long tokenWorkspaceId = null;
        Long tokenId = null;
        String workspaceHeader = request.getHeader(TOKEN_WORKSPACE_HEADER);
        if (workspaceHeader != null && !workspaceHeader.isBlank()) {
            try {
                tokenWorkspaceId = Long.parseLong(workspaceHeader.trim());
                String tokenHeader = request.getHeader(ACCESS_TOKEN_HEADER);
                tokenId = tokenHeader == null || tokenHeader.isBlank() ? null : Long.parseLong(tokenHeader.trim());
            } catch (NumberFormatException ex) {
                writeUnauthorized(response);
                return;
            }
            TokenScope.Decision decision = TokenScope.decide(request.getMethod(), request.getRequestURI(), tokenWorkspaceId);
            if (decision != TokenScope.Decision.ALLOW) {
                writeError(response, decision == TokenScope.Decision.NOT_FOUND
                        ? ErrorCode.WORKSPACE_NOT_FOUND : ErrorCode.PERMISSION_DENIED);
                return;
            }
        }
        try {
            CurrentUserHolder.set(new CurrentUser(userId, tokenWorkspaceId, tokenId));
            filterChain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
        }
    }

    /** 공유 공개·댓글 경로 — 헤더가 있으면(회원) 신원을 얹고, 없으면(비회원) 익명으로 통과한다 */
    private void filterSharePath(HttpServletRequest request, HttpServletResponse response,
                                 FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(USER_ID_HEADER);
        if (header != null && !header.isBlank()) {
            try {
                CurrentUserHolder.set(new CurrentUser(Long.parseLong(header.trim())));
            } catch (NumberFormatException ex) {
                writeUnauthorized(response);
                return;
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private static boolean isSharePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/core/shares");
    }

    /** 반응 토글(1.10.6)은 회원전용 — X-USER-ID 필수 경로다 */
    private static boolean isMemberOnlySharePath(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().endsWith("/reactions");
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        writeError(response, ErrorCode.AUTH_TOKEN_INVALID);
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ErrorResponse.of(code.getCode(), code.getDefaultMessage())));
    }
}
