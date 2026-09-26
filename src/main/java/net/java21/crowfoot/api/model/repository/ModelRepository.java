package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.Model;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ERD 문서 쓰기 접근 (JPA). */
public interface ModelRepository extends JpaRepository<Model, Long> {

    /** Workspace 내 이름 중복 검사 — UNIQUE(workspace_id, name) 앱 레벨 선검사 (1.2 409) */
    boolean existsByWorkspaceIdAndName(Long workspaceId, String name);

    /** 메타 변경(1.4) — 자기 자신 제외 이름 중복 검사 */
    boolean existsByWorkspaceIdAndNameAndIdNot(Long workspaceId, String name, Long id);

    Optional<Model> findByIdAndWorkspaceId(Long id, Long workspaceId);

    /** 템플릿 공개 목록(08-core/09-templates.md Section 2.1) — 워크스페이스 문서 전체를 갱신순으로 */
    List<Model> findByWorkspaceIdOrderByUpdatedAtDescIdDesc(Long workspaceId);

    /** 버전 경량 조회(1.9 협업 폴링) — content(최대 5MB)를 로드하지 않기 위한 프로젝션 */
    @Query("select m.version, m.updatedAt from Model m"
            + " where m.id = :id and m.workspaceId = :workspaceId")
    List<Object[]> findVersionRowByIdAndWorkspaceId(@Param("id") Long id,
                                                    @Param("workspaceId") Long workspaceId);

    /**
     * 문서 본체 저장(1.5) — version 일치 조건부 갱신(원자적 낙관적 잠금).
     * 갱신 행이 0이면 버전 불일치(409) 또는 대상 없음 — 호출부가 구분해 판정한다.
     * bulk update는 @UpdateTimestamp를 거치지 않으므로 갱신 일시를 파라미터로 받고,
     * clearAutomatically로 1차 캐시의 stale 엔터티를 제거해 이후 재조회가 갱신값을 보게 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Model m set m.content = :content, m.version = m.version + 1, m.updatedAt = :updatedAt"
            + " where m.id = :id and m.workspaceId = :workspaceId and m.version = :baseVersion")
    int updateContentIfVersionMatches(@Param("id") Long id,
                                      @Param("workspaceId") Long workspaceId,
                                      @Param("baseVersion") long baseVersion,
                                      @Param("content") String content,
                                      @Param("updatedAt") Instant updatedAt);
}
