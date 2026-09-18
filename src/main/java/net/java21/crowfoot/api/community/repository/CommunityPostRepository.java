package net.java21.crowfoot.api.community.repository;

import net.java21.crowfoot.api.community.domain.CommunityPost;
import org.springframework.data.jpa.repository.JpaRepository;

/** 커뮤니티 게시글 단건 CRUD (코드 컨벤션 §5 — Spring Data는 단건만 담당) */
public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {
}
