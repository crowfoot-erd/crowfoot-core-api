package net.java21.crowfoot.api.notification.repository;

import net.java21.crowfoot.api.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 알림 쓰기 접근 (JPA) — 읽음 처리·안읽음 카운트·좋아요 재토글 억제 검사·보존 정리.
 * 목록(조인 표시명·페이징)은 {@link NotificationQueryRepository}다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 좋아요 재토글 억제 검사(11-notification.md Section 3.1) — 같은 수신자·행위자·문서의 REACTION_ADDED가
     *  이미 있으면 끄고 다시 켜도 새 알림을 만들지 않는다(읽음 여부 무관 — 유니크 제약이 아닌 exists 판정) */
    boolean existsByUserIdAndTypeAndActorUserIdAndModelId(Long userId, String type,
                                                          Long actorUserId, Long modelId);

    /** 안읽음 카운트 — 헤더 벨 배지의 원천(웹 폴링 30초) */
    long countByUserIdAndReadAtIsNull(Long userId);

    /** 수신자 소유 알림 확보 — 없는 알림·타인의 알림을 같은 404로 감춘다(존재 은닉) */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    /** 전체 읽음 — 수신자의 안읽음 행에 read_at을 일괄 스탬프한다. 반환값은 갱신 행 수 */
    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") long userId, @Param("now") Instant now);

    /** 보존 정리 배치(11-notification.md Section 7) — createdAt이 cutoff 미만인 행 삭제 */
    @Modifying
    @Query("delete from Notification n where n.createdAt < :cutoff")
    long deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
