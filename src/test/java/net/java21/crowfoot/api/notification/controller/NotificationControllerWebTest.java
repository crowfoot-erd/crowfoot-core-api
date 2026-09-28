package net.java21.crowfoot.api.notification.controller;

import net.java21.crowfoot.api.notification.dto.NotificationResponse;
import net.java21.crowfoot.api.notification.service.NotificationService;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 API 웹 계층 테스트 (08-core/11-notification.md Section 5) — 전부 회원전용(XUserIdFilter 기본
 * 인증 경로라 무변경): X-USER-ID 신원 전달·페이징 파라미터·게스트 행의 actorUserId 생략(NON_NULL)·
 * 존재 은닉 404·무헤더 401.
 */
@WebMvcTest(NotificationController.class)
class NotificationControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    @DisplayName("목록은 200 — 페이징 메타·게스트 행의 actorUserId 생략·read 플래그")
    void listReturnsPagedNotifications() throws Exception {
        given(notificationService.list(7L, null, null)).willReturn(ListApiResponse.paged(List.of(
                new NotificationResponse("41", "COMMENT_CREATED", null, "지나가던 DBA",
                        "501", "주문 ERD", "77", false, Instant.parse("2026-09-28T10:00:00Z"))), 1, 20, 1));

        mockMvc.perform(get("/core/notifications").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].id").value("41"))
                .andExpect(jsonPath("$.responses[0].type").value("COMMENT_CREATED"))
                .andExpect(jsonPath("$.responses[0].actorUserId").doesNotExist()) // 게스트 — NON_NULL 생략
                .andExpect(jsonPath("$.responses[0].actorDisplayName").value("지나가던 DBA"))
                .andExpect(jsonPath("$.responses[0].modelId").value("501"))
                .andExpect(jsonPath("$.responses[0].workspaceId").value("77"))
                .andExpect(jsonPath("$.responses[0].read").value(false))
                .andExpect(jsonPath("$.responses[0].createdAt").value("2026-09-28T10:00:00Z"));
    }

    @Test
    @DisplayName("목록은 page·size 쿼리 파라미터를 서비스에 전달한다 — 드롭다운은 page=1&size=10")
    void listPassesPagingParams() throws Exception {
        given(notificationService.list(7L, 1, 10)).willReturn(ListApiResponse.paged(List.of(), 1, 10, 0));

        mockMvc.perform(get("/core/notifications")
                        .header("X-USER-ID", "7")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.responses").isEmpty());
    }

    @Test
    @DisplayName("안읽음 카운트는 response에 long을 실어 내려준다 — 벨 배지 원천")
    void unreadCountReturnsLong() throws Exception {
        given(notificationService.unreadCount(7L)).willReturn(12L);

        mockMvc.perform(get("/core/notifications/unread-count").header("X-USER-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value(12));
    }

    @Test
    @DisplayName("읽음 처리는 신원·알림 id를 전달하고 200 — 멱등(서비스가 판정)")
    void markReadReturnsOk() throws Exception {
        mockMvc.perform(patch("/core/notifications/41/read").header("X-USER-ID", "7"))
                .andExpect(status().isOk());

        org.mockito.BDDMockito.then(notificationService).should().markRead(7L, 41L);
    }

    @Test
    @DisplayName("타인의 알림(·없는 알림)은 404 NOTIFICATION_NOT_FOUND — 존재 은닉")
    void markReadForeignNotificationIs404() throws Exception {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationService).markRead(7L, 99L);

        mockMvc.perform(patch("/core/notifications/99/read").header("X-USER-ID", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("전체 읽음은 신원을 전달하고 200")
    void markAllReadReturnsOk() throws Exception {
        mockMvc.perform(post("/core/notifications/read-all").header("X-USER-ID", "7"))
                .andExpect(status().isOk());

        org.mockito.BDDMockito.then(notificationService).should().markAllRead(7L);
    }

    @Test
    @DisplayName("X-USER-ID 헤더가 없으면 401 — 알림은 회원전용(게이트웨이·필터 무변경의 기본 경로)")
    void missingUserIdHeaderIsUnauthorized() throws Exception {
        mockMvc.perform(get("/core/notifications"))
                .andExpect(status().isUnauthorized());
    }
}
