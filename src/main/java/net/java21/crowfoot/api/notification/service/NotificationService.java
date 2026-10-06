package net.java21.crowfoot.api.notification.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.notification.domain.Notification;
import net.java21.crowfoot.api.notification.dto.NotificationResponse;
import net.java21.crowfoot.api.notification.repository.NotificationQueryRepository;
import net.java21.crowfoot.api.notification.repository.NotificationQueryRepository.NotificationRow;
import net.java21.crowfoot.api.notification.repository.NotificationRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 알림 조회·읽음 처리 (08-core/11-notification.md Section 5) — 수신자 본인의 알림만 다룬다
 * (신원은 컨트롤러가 CurrentUserHolder로 확보). 행의 갱신은 read_at 스탬프뿐이다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final NotificationQueryRepository queryRepository;
    private final NotificationRepository notificationRepository;

    /** 알림 목록 — 최신순(id desc) 오프셋 페이징. page·size 정규화는 공통 페이징 규칙을 따른다 */
    @Transactional(readOnly = true)
    public ListApiResponse<NotificationResponse> list(long userId, Integer page, Integer size) {
        int resolvedPage = (page == null || page < 1) ? 1 : page;
        int resolvedSize = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        long totalCount = queryRepository.countByUserId(userId);
        if (totalCount == 0) {
            return ListApiResponse.paged(List.of(), resolvedPage, resolvedSize, 0);
        }
        List<NotificationResponse> responses = queryRepository
                .findByUserIdOrderByIdDesc(userId, (long) (resolvedPage - 1) * resolvedSize, resolvedSize)
                .stream()
                .map(NotificationService::toResponse)
                .toList();
        return ListApiResponse.paged(responses, resolvedPage, resolvedSize, totalCount);
    }

    /** 안읽음 카운트 — 헤더 벨 배지의 원천(웹 폴링 30초) */
    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /** 읽음 처리(멱등) — 이미 읽은 알림도 그대로 200. 없는 알림·타인의 알림은 같은 404(존재 은닉) */
    @Transactional
    public void markRead(long userId, long notificationId) {
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
        }
    }

    /** 전체 읽음 — 안읽음 행에 read_at 일괄 스탬프. 반환값은 갱신 행 수 */
    @Transactional
    public int markAllRead(long userId) {
        return notificationRepository.markAllRead(userId, Instant.now());
    }

    /** 행 → 응답 — actor 표시명 조립(회원 = users.name, 게스트 = 별명 스냅샷)은 여기서 끝낸다 */
    private static NotificationResponse toResponse(NotificationRow row) {
        return new NotificationResponse(row.id().toString(), row.type(),
                row.actorUserId() == null ? null : row.actorUserId().toString(),
                row.actorUserId() != null ? row.actorName() : row.actorNickname(),
                row.modelId() == null ? null : row.modelId().toString(), row.modelName(),
                row.workspaceId() == null ? null : row.workspaceId().toString(),
                row.postId() == null ? null : row.postId().toString(), row.postTitle(),
                row.commentId() == null ? null : row.commentId().toString(),
                row.readAt() != null, row.createdAt());
    }
}
