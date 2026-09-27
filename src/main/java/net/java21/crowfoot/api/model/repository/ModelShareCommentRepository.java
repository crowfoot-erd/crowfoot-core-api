package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.ModelShareComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 공유 문서 댓글 접근 (JPA) — 08-core/02-model.md Section 1.10.7. */
public interface ModelShareCommentRepository extends JpaRepository<ModelShareComment, Long> {

    /** 링크의 댓글 목록 — id 오름차순 전체(무페이징 — 커뮤니티 댓글 관례). 읽기 경로는 조인 조회 리포지토리를 쓴다 */
    List<ModelShareComment> findByShareIdOrderByIdAsc(Long shareId);

    /** 링크 경계 안 댓글 조회 — 타 링크 소속 commentId 오용 방지 */
    Optional<ModelShareComment> findByIdAndShareId(Long id, Long shareId);

    /** 원댓글의 답글 수 — 원댓글 삭제 시 comment_count 동반 감소(1+답글 수) 계산 */
    long countByParentCommentId(Long parentCommentId);
}
