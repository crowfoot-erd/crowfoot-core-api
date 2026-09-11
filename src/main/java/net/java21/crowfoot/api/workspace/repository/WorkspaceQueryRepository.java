package net.java21.crowfoot.api.workspace.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.workspace.domain.QWorkspace;
import net.java21.crowfoot.api.workspace.domain.Workspace;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Workspace 조회(Querydsl). */
@Repository
@RequiredArgsConstructor
public class WorkspaceQueryRepository {

    private final JPAQueryFactory query;

    /** id 목록 조회 — 내 워크스페이스 목록(1회 조합)의 실체 페치 */
    public List<Workspace> findByIdIn(List<Long> ids) {
        QWorkspace workspace = QWorkspace.workspace;
        return query.selectFrom(workspace)
                .where(workspace.id.in(ids))
                .orderBy(workspace.id.asc())
                .fetch();
    }
}
