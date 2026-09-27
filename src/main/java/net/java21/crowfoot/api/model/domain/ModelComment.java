package net.java21.crowfoot.api.model.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 문서 댓글 (08-core/02-model.md Section 1.10.7) — 스레드는 문서(model) 단위다(2026-09-28 재설계:
 * 공개 뷰어 토큰 경로와 문서 열기 멤버 경로가 같은 스레드를 보고 링크 철회와 무관하게 살아 있다).
 * 회원(계정명)·비회원(별명+비밀번호) 원댓글 + 오너 답글 1단계. content는 plain text(마크다운 아님 —
 * community_comments 관례). 답글은 오너만 달 수 있고 답글의 답글은 서버가 차단한다
 * (parentCommentId가 가리키는 행이 이미 답글이면 서비스가 400).
 *
 * <p>행 형태는 둘 중 하나다(CHECK 제약 ck_model_comments_shape과 1:1) —
 * 비회원: nickname·passwordHash 있고 authorUserId 없음 / 회원·오너: authorUserId만 있고 nickname·passwordHash 없음.
 * 생성자 2종이 이 불변식을 강제한다. authorUserId는 논리 참조 users.id(FK 없음 — 그룹 경계 관례).
 * 비밀번호는 PBKDF2 해시로만 저장(평문 보관 없음) — 수정·삭제 시 GuestPasswordHasher가 검증한다.
 * 문서 삭제·원댓글 삭제 시 FK CASCADE로 함께 사라진다.
 */
@Entity
@Table(name = "model_comments", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class ModelComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 문서 — 문서 단위 스레드의 앵커(2026-09-28 링크 단위에서 이관) */
    private Long modelId;

    /** 원댓글 id — null이면 원댓글 */
    private Long parentCommentId;

    /** 비회원 별명(≤30자) — 회원 댓글·오너 답글은 null(표시명은 users.name) */
    private String nickname;

    /** 댓글 본문 — plain text, ≤1,000자 */
    private String content;

    /** 작성 회원(일반 회원 댓글·오너 답글) — 비회원은 null. 오너 판정은 model.createdBy와 비교(조회 시) */
    private Long authorUserId;

    /** 비회원 댓글 비밀번호 해시(PBKDF2 자기서술형) — 회원 댓글은 null. 수정·삭제 시 검증 */
    private String passwordHash;

    private Instant createdAt;

    /** 마지막 수정 시각 — 생성 시각과 다르면 응답 edited=true */
    private Instant updatedAt;

    /**
     * INSERT 시 두 시각을 같은 판정 값으로 새긴다 — @CreationTimestamp·@UpdateTimestamp를 함께 쓰면
     * 각자 시계를 읽어 마이크로초가 어긋나고, 방금 만든 댓글도 edited=true로 보였다(2026-09-27 검증에서 발견).
     * 이후 UPDATE마다 @PreUpdate가 updated_at만 갱신한다.
     */
    @PrePersist
    void stampCreation() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void stampUpdate() {
        this.updatedAt = Instant.now();
    }

    /** 비회원 댓글 — parentCommentId는 항상 null(답글은 오너 전용) */
    public ModelComment(Long modelId, String nickname, String content, String passwordHash) {
        this.modelId = modelId;
        this.nickname = nickname;
        this.content = content;
        this.passwordHash = passwordHash;
    }

    /** 회원 댓글·오너 답글 — 일반 회원은 parentCommentId null, 오너 답글은 필수 */
    public ModelComment(Long modelId, Long parentCommentId, String content, Long authorUserId) {
        this.modelId = modelId;
        this.parentCommentId = parentCommentId;
        this.content = content;
        this.authorUserId = authorUserId;
    }
}
