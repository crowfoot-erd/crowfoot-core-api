package net.java21.crowfoot.api.notification.service;

import net.java21.crowfoot.api.notification.domain.Notification;
import net.java21.crowfoot.api.notification.dto.NotificationResponse;
import net.java21.crowfoot.api.notification.repository.NotificationQueryRepository;
import net.java21.crowfoot.api.notification.repository.NotificationQueryRepository.NotificationRow;
import net.java21.crowfoot.api.notification.repository.NotificationRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 알림 조회·읽음 처리 단위 테스트 (08-core/11-notification.md Section 5) — 페이징 정규화·
 * actor 표시명 조립(회원 name / 게스트 별명)·안읽음 카운트·존재 은닉 404·멱등 읽음·전체 읽음.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Instant CREATED = Instant.parse("2026-09-28T10:00:00Z");

    @Mock
    private NotificationQueryRepository queryRepository;
    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    @DisplayName("목록은 최신순 오프셋 페이징 — 표시명 조립(회원=name·게스트=별명)·read 플래그·문자열 id까지 완성한다")
    void listMapsRowsWithPaging() {
        given(queryRepository.countByUserId(2L)).willReturn(21L);
        given(queryRepository.findByUserIdOrderByIdDesc(2L, 20L, 20)).willReturn(List.of(
                new NotificationRow(41L, "COMMENT_CREATED", 8L, "다른회원", null, 501L, "주문 ERD",
                        77L, null, CREATED),
                new NotificationRow(40L, "COMMENT_CREATED", null, null, "지나가던 DBA", 501L, "주문 ERD",
                        77L, CREATED, CREATED.minusSeconds(60))));

        ListApiResponse<NotificationResponse> response = notificationService.list(2L, 2, 20);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalCount()).isEqualTo(21L);
        assertThat(response.responses()).hasSize(2);
        NotificationResponse member = response.responses().get(0);
        assertThat(member.id()).isEqualTo("41");
        assertThat(member.type()).isEqualTo("COMMENT_CREATED");
        assertThat(member.actorUserId()).isEqualTo("8");
        assertThat(member.actorDisplayName()).isEqualTo("다른회원");
        assertThat(member.modelId()).isEqualTo("501");
        assertThat(member.modelName()).isEqualTo("주문 ERD");
        assertThat(member.workspaceId()).isEqualTo("77");
        assertThat(member.read()).isFalse();
        NotificationResponse guest = response.responses().get(1);
        assertThat(guest.actorUserId()).isNull();               // 게스트 — NON_NULL로 응답에서 생략
        assertThat(guest.actorDisplayName()).isEqualTo("지나가던 DBA");
        assertThat(guest.read()).isTrue();
    }

    @Test
    @DisplayName("페이징 정규화 — page<1→1, size<1→20, size>100→100(400 대신 경계 조정)")
    void listNormalizesPaging() {
        given(queryRepository.countByUserId(2L)).willReturn(0L);

        notificationService.list(2L, 0, 0);
        notificationService.list(2L, null, null);
        notificationService.list(2L, 3, 500);

        then(queryRepository).should(org.mockito.Mockito.times(3)).countByUserId(2L);
        then(queryRepository).should(never()).findByUserIdOrderByIdDesc(eq(2L), eq(0L), any(Integer.class));
        then(queryRepository).should(never()).findByUserIdOrderByIdDesc(2L, 0L, 20);
        then(queryRepository).should(never()).findByUserIdOrderByIdDesc(2L, 200L, 100); // 빈 목록 조기 반환
    }

    @Test
    @DisplayName("안읽음 카운트는 read_at IS NULL 행 수를 그대로 내려준다 — 벨 배지의 원천")
    void unreadCountCountsUnread() {
        given(notificationRepository.countByUserIdAndReadAtIsNull(2L)).willReturn(12L);

        assertThat(notificationService.unreadCount(2L)).isEqualTo(12L);
    }

    @Test
    @DisplayName("읽음 처리 — 수신자 소유 알림의 read_at을 스탬프한다(멱등: 이미 읽었으면 저장하지 않는다)")
    void markReadStampsAndIsIdempotent() {
        Notification unread = new Notification(2L, "REACTION_ADDED", 8L, null, 501L, "주문 ERD");
        given(notificationRepository.findByIdAndUserId(41L, 2L)).willReturn(Optional.of(unread));

        notificationService.markRead(2L, 41L);

        assertThat(unread.getReadAt()).isNotNull();
        then(notificationRepository).should().save(unread);

        Notification read = new Notification(2L, "REACTION_ADDED", 8L, null, 501L, "주문 ERD");
        read.setReadAt(CREATED);
        given(notificationRepository.findByIdAndUserId(42L, 2L)).willReturn(Optional.of(read));

        notificationService.markRead(2L, 42L);

        assertThat(read.getReadAt()).isEqualTo(CREATED);
        then(notificationRepository).should(never()).save(read);
    }

    @Test
    @DisplayName("없는 알림·타인의 알림은 같은 404 NOTIFICATION_NOT_FOUND — 존재 은닉")
    void markReadHidesMissingAndForeign() {
        given(notificationRepository.findByIdAndUserId(99L, 2L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markRead(2L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("전체 읽음 — 수신자의 안읽음 행에 now를 일괄 스탬프하고 갱신 수를 돌려준다")
    void markAllReadUpdatesUnreadRows() {
        given(notificationRepository.markAllRead(eq(2L), any(Instant.class))).willReturn(7);

        assertThat(notificationService.markAllRead(2L)).isEqualTo(7);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        then(notificationRepository).should().markAllRead(eq(2L), captor.capture());
        assertThat(captor.getValue()).isCloseTo(Instant.now(), within(Duration.ofSeconds(60))); // 호출 시각 now
    }
}
