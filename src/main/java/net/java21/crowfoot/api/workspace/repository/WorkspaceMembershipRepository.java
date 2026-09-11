package net.java21.crowfoot.api.workspace.repository;

import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.domain.WorkspaceMembership;
import org.springframework.data.jpa.repository.JpaRepository;

/** 단건 CRUD·bulk delete 전용 — 조회·집계는 WorkspaceMembershipQueryRepository(Querydsl)를 사용한다. */
public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, Long> {

    /** Workspace 삭제 — 멤버십 전량 물리 삭제 (같은 트랜잭션) */
    long deleteByWorkspaceId(Long workspaceId);

    /** 팀 해체 — 이 팀(TEAM 피부여자)의 부여 행 전량 삭제. 개인 WS에 팀 부여된 행이 있을 수 있다 */
    long deleteByGranteeTypeAndTeamId(GranteeType granteeType, Long teamId);
}
