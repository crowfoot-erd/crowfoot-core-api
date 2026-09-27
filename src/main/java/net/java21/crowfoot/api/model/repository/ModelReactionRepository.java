package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.ModelReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 문서 반응 접근 (JPA) — 08-core/02-model.md Section 1.10.6. 회원전용(user_id), 문서(model) 단위(2026-09-28 이관). */
public interface ModelReactionRepository extends JpaRepository<ModelReaction, Long> {

    /** 이 회원이 이미 반응했는가 — 피드백 목록의 reacted 초기값 */
    boolean existsByModelIdAndUserId(Long modelId, Long userId);

    /**
     * 반응 삽입 시도(토글 ON 경로) — 이미 (model_id, user_id) 행이 있으면 충돌을 무시하고 0,
     * 성공하면 1. UNIQUE 제약 판정을 DB에 맡긴 원자 경로라 동시 토글에도 예외가 새지 않는다.
     * created_at은 DB 기본값(now()).
     */
    @Modifying
    @Query(value = """
            insert into crowfoot_core.model_reactions (model_id, user_id)
            values (:modelId, :userId)
            on conflict do nothing
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("modelId") long modelId, @Param("userId") long userId);

    /** 반응 제거(토글 OFF 경로) — 행이 이미 없으면 0건, 멱등 */
    void deleteByModelIdAndUserId(Long modelId, Long userId);
}
