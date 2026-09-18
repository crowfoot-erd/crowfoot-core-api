package net.java21.crowfoot.api.community.repository;

import net.java21.crowfoot.api.community.domain.CommunityComment;
import org.springframework.data.jpa.repository.JpaRepository;

/** 커뮤니티 코멘트 단건 CRUD — 게시글 삭제 동반 소멸은 FK CASCADE가 담당(쿼리 메서드 불필요) */
public interface CommunityCommentRepository extends JpaRepository<CommunityComment, Long> {
}
