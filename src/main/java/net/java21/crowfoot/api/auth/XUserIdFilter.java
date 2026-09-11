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
 * <p>구현 경로 {@code /core/**}는 Gateway가 주입한 X-USER-ID(sub 문자열)를 요구한다 —
 * 헤더 없는 요청은 Gateway를 거치지 않은 요청이므로 401로 거부한다(공통 실패 포맷).
 * 제외 경로: {@code /internal/**}(내부망 — 인증 서버 호출), {@code /core/providers}(공개),
 * {@code /actuator/**}(헬스체크).
 */
@Component
@RequiredArgsConstructor
public class XUserIdFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-USER-ID";

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/core")                       // /core/** 외에는 인증 대상 아님
                || "/core/providers".equals(path)              // 활성 제공자 목록 — 공개
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
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
        try {
            CurrentUserHolder.set(new CurrentUser(userId));
            filterChain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.AUTH_TOKEN_INVALID;
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ErrorResponse.of(code.getCode(), code.getDefaultMessage())));
    }
}
