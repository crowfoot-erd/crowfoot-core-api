package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.ModelComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 문서 댓글 접근 (JPA) — 08-core/02-model.md Section 1.10.7. 문서(model) 단위 스레드(2026-09-28 이관). */
public interface ModelCommentRepository extends JpaRepository<ModelComment, Long> {

    /** 문서의 댓글 목록 — id 오름차순 전체(무페이징 — 커뮤니티 댓글 관례). 읽기 경로는 조인 조회 리포지토리를 쓴다 */
    List<ModelComment> findByModelIdOrderByIdAsc(Long modelId);

    /** 문서 경계 안 댓글 조회 — 타 문서 소속 commentId 오용 방지 */
    Optional<ModelComment> findByIdAndModelId(Long id, Long modelId);

    /** 원댓글의 답글 수 — 원댓글 삭제 시 comment_count 동반 감소(1+답글 수) 계산 */
    long countByParentCommentId(Long parentCommentId);
}
