package net.java21.crowfoot.api.community.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 커뮤니티 게시글 (08-core/08-community.md Section 2) — 1행 = 1글(릴리스 노트는 버전별 1글).
 * content는 마크다운 원문이며 이미지는 base64 data URL로 인라인된다(파일 저장소 없음).
 * created_by는 논리 참조(users) — 수정·삭제는 작성자 본인 또는 관리자.
 */
@Entity
@Table(name = "community_posts", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class CommunityPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private CommunityBoard board;

    private String title;

    /** 마크다운 원문 — 최대 1,000,000자(base64 인라인 이미지 포함), 목록 조회에서는 로드하지 않는다 */
    private String content;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public CommunityPost(CommunityBoard board, String title, String content, Long createdBy) {
        this.board = board;
        this.title = title;
        this.content = content;
        this.createdBy = createdBy;
    }
}
