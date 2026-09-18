package net.java21.crowfoot.api.community.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 커뮤니티 코멘트 (08-core/08-community.md Section 2) — FEEDBACK 게시글 전용(릴리스 노트는 읽기 전용).
 * content는 plain text(마크다운 아님). 게시글 삭제 시 FK CASCADE로 함께 사라진다.
 */
@Entity
@Table(name = "community_comments", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class CommunityComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long postId;

    private String content;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public CommunityComment(Long postId, String content, Long createdBy) {
        this.postId = postId;
        this.content = content;
        this.createdBy = createdBy;
    }
}
