package net.java21.crowfoot.api.internal.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.connection.crypto.ConnectionCrypto;
import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.connection.repository.DbConnectionRepository;
import net.java21.crowfoot.api.internal.dto.ConnectionAccessResponse;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository.EffectiveRole;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 매니저(crowfoot-database-manager) 지원 내부 API — 접근 확인 (08-core/15-internal-api.md Section 2.1).
 *
 * <p>사용자가 그 커넥션의 데이터를 다룰 수 있는지 판정하고, 통과하면 접속 정보(복호화한 비밀번호 포함)를
 * 돌려준다. 판정 순서는 스펙 그대로다 — ① 비멤버는 존재 은닉 404 WORKSPACE_NOT_FOUND
 * ② 커넥션이 없거나 그 워크스페이스 소속이 아니면 404 CONNECTION_NOT_FOUND ③ Editor 미만은 403.
 * 권한 기준은 커넥션의 다른 사용 경로(테스트·리버스·배포)와 같은 Editor 이상이다(06-connection.md Section 1).
 *
 * <p>읽기 전용이며 Gateway 라우팅 제외, 내부망에서만 연다(Internal* 관례). 이 호출은 감사 기록을 남기지
 * 않는다 — DB 매니저가 동작 종류에 맞는 액션 코드로 따로 기록한다(감사 기록 내부 API).
 */
@Service
@RequiredArgsConstructor
public class InternalConnectionAccessService {

    private final RoleChecker roleChecker;
    private final DbConnectionRepository connectionRepository;
    private final ConnectionCrypto crypto;

    @Transactional(readOnly = true)
    public ConnectionAccessResponse access(long connectionId, long userId, long workspaceId) {
        roleChecker.requireMember(userId, workspaceId);                                   // ① 404 WORKSPACE_NOT_FOUND
        DbConnection connection = connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND)); // ② 404
        EffectiveRole role = roleChecker.requireEditor(userId, workspaceId);              // ③ 403 PERMISSION_DENIED
        return ConnectionAccessResponse.of(connection, role.code(), crypto.decrypt(connection.getPassword()));
    }
}
