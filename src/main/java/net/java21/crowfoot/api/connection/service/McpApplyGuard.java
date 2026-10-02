package net.java21.crowfoot.api.connection.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUser;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * MCP 반영 허용 판정 (08-core/06-connection.md Section 2.1).
 * 워크스페이스 액세스 토큰으로 온 요청(MCP)은 데이터베이스의 구조를 바꾸는 실행(최초 배포, 마이그레이션 DDL 실행)을
 * 허용된 커넥션에만 할 수 있다 — 매니지드 데이터베이스이거나 "MCP 반영 허용"이 켜진 커넥션이다.
 * 웹에서 로그인한 사용자의 요청은 이 설정과 무관하다.
 */
@Component
@RequiredArgsConstructor
public class McpApplyGuard {

    private final ManagedDatabaseRepository managedDatabaseRepository;

    public void requireAllowed(DbConnection connection) {
        CurrentUser current = CurrentUserHolder.getOrNull();
        if (current == null || !current.viaToken()) {
            return;
        }
        if (connection.isMcpApplyAllowed() || managedDatabaseRepository.findByConnectionId(connection.getId()).isPresent()) {
            return;
        }
        throw BusinessException.of(ErrorCode.PERMISSION_DENIED, "detail.connection.mcp-apply-not-allowed");
    }
}
