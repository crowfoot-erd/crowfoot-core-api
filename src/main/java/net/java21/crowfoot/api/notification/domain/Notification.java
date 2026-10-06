package net.java21.crowfoot.api.notification.domain;

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
 * 알림 (08-core/11-notification.md Section 4) — 피드백 3종 이벤트의 수신자별 기록.
 * 알림 문구는 서버에 두지 않는다 — type만 내려 웹이 i18n로 렌더한다(개명·탈퇴와 무관하게
 * actor 표시명만 조회 시 조인한다). userId(수신자)는 users.id 논리 참조(FK 없음 — 그룹 경계 관례),
 * modelId는 FK CASCADE라 문서가 지워지면 알림도 동반 삭제된다. modelName은 이벤트 시점 스냅샷.
 * 행의 갱신은 읽음 처리(read_at 스탬프)뿐이다 — 재발행은 새 행이다.
 */
@Entity
@Table(name = "notifications", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    /** 내 문서에 댓글(게스트·멤버 원댓글) — 수신자 = 문서 오너 */
    public static final String TYPE_COMMENT_CREATED = "COMMENT_CREATED";

    /** 내 문서 좋아요(토글 on만) — 수신자 = 문서 오너 */
    public static final String TYPE_REACTION_ADDED = "REACTION_ADDED";

    /** 내 원댓글에 오너 답글 — 수신자 = 원댓글 작성자(회원만 — 게스트는 수신 불가) */
    public static final String TYPE_OWNER_REPLIED = "OWNER_REPLIED";

    /** 내 "제안 및 신고" 글에 댓글 — 수신자 = 게시글 작성자 (Section 2.1) */
    public static final String TYPE_COMMUNITY_COMMENT_CREATED = "COMMUNITY_COMMENT_CREATED";

    /** "제안 및 신고"에 새 글 — 수신자 = 관리자 전원(작성자 본인 제외) (Section 2.2) */
    public static final String TYPE_FEEDBACK_POST_CREATED = "FEEDBACK_POST_CREATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 수신자 — 알림 소유자(읽음·삭제 판정의 기준) */
    private Long userId;

    /** 이벤트 종류 — 폐쇄 집합(TYPE_* 상수 5종) */
    private String type;

    /** 행위자 회원 id — 게스트 댓글이면 null */
    private Long actorUserId;

    /** 게스트 별명 스냅샷(≤30자) — 회원 행위자면 null(표시명은 조회 시 users.name 조인) */
    private String actorNickname;

    /** 문서 알림의 문서 — 게시글 알림은 null */
    private Long modelId;

    /** 이벤트 시점 문서 이름 스냅샷(≤100자) — 이후 개명과 무관. 게시글 알림은 null */
    private String modelName;

    /** 게시글 알림의 게시글(FK CASCADE) — 문서 알림은 null */
    private Long postId;

    /** 이벤트 시점 게시글 제목 스냅샷(≤200자) */
    private String postTitle;

    /** 댓글 알림의 댓글 — 알림에서 그 댓글로 바로 간다. 댓글이 지워지면 null(FK SET NULL) */
    private Long commentId;

    /** null = 안읽음(벨 배지 원천) — 읽음 처리 시각 */
    private Instant readAt;

    @CreationTimestamp
    private Instant createdAt;

    public Notification(Long userId, String type, Long actorUserId, String actorNickname,
                        Long modelId, String modelName) {
        this.userId = userId;
        this.type = type;
        this.actorUserId = actorUserId;
        this.actorNickname = actorNickname;
        this.modelId = modelId;
        this.modelName = modelName;
    }

    /** 게시글 알림(Section 2.1) — 문서 대신 게시글을 가리킨다 */
    public static Notification forPost(Long userId, String type, Long actorUserId, Long postId, String postTitle,
                                       Long commentId) {
        Notification notification = new Notification(userId, type, actorUserId, null, null, null);
        notification.postId = postId;
        notification.postTitle = postTitle;
        notification.commentId = commentId;
        return notification;
    }
}
