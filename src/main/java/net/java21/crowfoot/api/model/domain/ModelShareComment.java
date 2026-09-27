package net.java21.crowfoot.api.model.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 공유 문서 댓글 (08-core/02-model.md Section 1.10.7) — 익명(별명) 원댓글 + 오너·익명 1단계 답글.
 * content는 plain text(마크다운 아님 — community_comments 관례). 답글의 답글은 앱 레벨에서 차단한다
 * (parentCommentId가 가리키는 행이 이미 답글이면 서비스가 400).
 *
 * <p>행 형태는 둘 중 하나다(CHECK 제약 ck_model_share_comments_shape과 1:1) —
 * 익명: nickname·visitorKey 있고 authorUserId 없음 / 오너 답글: authorUserId만 있고 nickname·visitorKey 없음.
 * 생성자 2종이 이 불변식을 강제한다. authorUserId는 논리 참조 users.id(FK 없음 — 그룹 경계 관례).
 * 링크 철회·문서 삭제·원댓글 삭제 시 FK CASCADE로 함께 사라진다.
 */
@Entity
@Table(name = "model_share_comments", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class ModelShareComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shareId;

    /** 원댓글 id — null이면 원댓글 */
    private Long parentCommentId;

    /** 익명 별명(≤30자) — 오너 답글은 null(표시명은 작성자 users.name) */
    private String nickname;

    /** 댓글 본문 — plain text, ≤1,000자 */
    private String content;

    /** 오너 답글 작성자(문서 작성자·관리자) — 익명은 null */
    private Long authorUserId;

    /** 익명 작성자의 쿠키 키(crowfoot_share_actor) — 본인 삭제 판정. 오너 답글은 null */
    private String visitorKey;

    @CreationTimestamp
    private Instant createdAt;

    /** 익명 댓글/답글 — parentCommentId null이면 원댓글 */
    public ModelShareComment(Long shareId, Long parentCommentId, String nickname, String content,
                             String visitorKey) {
        this.shareId = shareId;
        this.parentCommentId = parentCommentId;
        this.nickname = nickname;
        this.content = content;
        this.visitorKey = visitorKey;
    }

    /** 오너 답글 — 답글 전용이라 parentCommentId 필수 */
    public ModelShareComment(Long shareId, Long parentCommentId, String content, Long authorUserId) {
        this.shareId = shareId;
        this.parentCommentId = parentCommentId;
        this.content = content;
        this.authorUserId = authorUserId;
    }
}
