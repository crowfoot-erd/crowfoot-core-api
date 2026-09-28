package net.java21.crowfoot.api.notification.controller;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.notification.dto.NotificationResponse;
import net.java21.crowfoot.api.notification.service.NotificationService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 API (08-core/11-notification.md Section 5) — 전부 회원전용이라 Gateway·XUserIdFilter
 * 무변경(기본 인증 경로). 수신(실시간 푸시)은 하지 않는다 — 웹이 30초 폴링으로 unread-count를
 * 확인하고 목록은 헤더 벨 드롭다운(최근 10건)과 전체 페이지가 같은 API를 page·size로 나눠 쓴다.
 */
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 알림 목록 — 최신순 오프셋 페이징(page<1→1·size<1→20·size>100→100은 서비스가 정규화) */
    @GetMapping("/core/notifications")
    public ListApiResponse<NotificationResponse> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return notificationService.list(CurrentUserHolder.get().userId(), page, size);
    }

    /** 안읽음 카운트 — 헤더 벨 배지의 원천 */
    @GetMapping("/core/notifications/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.success(notificationService.unreadCount(CurrentUserHolder.get().userId()));
    }

    /** 읽음 처리(멱등) — 없는 알림·타인의 알림은 404 NOTIFICATION_NOT_FOUND(존재 은닉) */
    @PatchMapping("/core/notifications/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable("id") long id) {
        notificationService.markRead(CurrentUserHolder.get().userId(), id);
        return ApiResponse.success();
    }

    /** 전체 읽음 — 수신자의 안읽음 행 일괄 스탬프 */
    @PostMapping("/core/notifications/read-all")
    public ApiResponse<Void> markAllRead() {
        notificationService.markAllRead(CurrentUserHolder.get().userId());
        return ApiResponse.success();
    }
}
