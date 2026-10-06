package net.java21.crowfoot.api.notification.service;

import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelComment;
import net.java21.crowfoot.api.notification.domain.Notification;
import net.java21.crowfoot.api.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * 알림 발행 판정 단위 테스트 (08-core/11-notification.md Section 3) — 수신자 확정·자기 행위 스킵·
 * 게스트 수신 불가·좋아요 재토글 억제(유니크 제약이 아닌 exists 판정)와 best-effort(발행 실패가
 * 본류를 죽이지 않는다)를 검증한다. 훅 부착 자체는 ShareFeedbackServiceTest가 본다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationRecorderTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationWriter notificationWriter;
    @Mock
    private net.java21.crowfoot.api.account.repository.UserRepository userRepository;

    @InjectMocks
    private NotificationRecorder recorder;

    @Test
    @DisplayName("제안 및 신고 글에 남이 댓글을 달면 글쓴이에게 COMMUNITY_COMMENT_CREATED — 게시글 id·제목 스냅샷, 문서 없음")
    void communityCommentNotifiesPostAuthor() {
        recorder.notifyCommunityCommentCreated(41L, 2L, "배포 SQL 문법 오류", 7L, 61L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationWriter).should().insert(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(2L);          // 수신자 = 글쓴이
        assertThat(saved.getType()).isEqualTo("COMMUNITY_COMMENT_CREATED");
        assertThat(saved.getActorUserId()).isEqualTo(7L);
        assertThat(saved.getPostId()).isEqualTo(41L);
        assertThat(saved.getPostTitle()).isEqualTo("배포 SQL 문법 오류");
        assertThat(saved.getCommentId()).isEqualTo(61L);
        assertThat(saved.getModelId()).isNull();
        assertThat(saved.getModelName()).isNull();
    }

    @Test
    @DisplayName("관리자가 일반 사용자 글에 댓글을 달면 그 사용자에게 알림이 간다")
    void adminCommentNotifiesPostAuthor() {
        recorder.notifyCommunityCommentCreated(41L, 9L, "검색 필터 개선 제안", 2L, 63L); // 2 = 관리자, 9 = 글쓴이

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationWriter).should().insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(9L);
        assertThat(captor.getValue().getActorUserId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("제안 및 신고 새 글은 관리자 전원에게 FEEDBACK_POST_CREATED — 작성자인 관리자 본인은 빠진다")
    void feedbackPostNotifiesAdminsExceptAuthor() {
        org.mockito.BDDMockito.given(userRepository.findActiveAdminIds()).willReturn(java.util.List.of(2L, 5L));

        recorder.notifyFeedbackPostCreated(41L, "검색 필터 개선 제안", 7L); // 일반 사용자 글
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationWriter).should(org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(Notification::getUserId).containsExactly(2L, 5L);
        assertThat(captor.getAllValues()).allSatisfy(n -> {
            assertThat(n.getType()).isEqualTo("FEEDBACK_POST_CREATED");
            assertThat(n.getPostId()).isEqualTo(41L);
            assertThat(n.getActorUserId()).isEqualTo(7L);
        });
    }

    @Test
    @DisplayName("관리자가 직접 쓴 제안 및 신고 글은 본인에게 알림이 없다")
    void feedbackPostByAdminSkipsSelf() {
        org.mockito.BDDMockito.given(userRepository.findActiveAdminIds()).willReturn(java.util.List.of(2L));

        recorder.notifyFeedbackPostCreated(42L, "운영 공지 초안", 2L);

        then(notificationWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("글쓴이가 자기 글에 댓글을 달면 알림이 없다")
    void ownCommunityCommentIsSkipped() {
        recorder.notifyCommunityCommentCreated(41L, 2L, "배포 SQL 문법 오류", 2L, 62L);

        then(notificationWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("타인의 회원 원댓글은 오너에게 COMMENT_CREATED — actor=회원 id, 별명 없음")
    void memberCommentNotifiesOwner() {
        Model model = model(7L);

        recorder.notifyCommentCreated(model, 8L, null);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationWriter).should().insert(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);          // 수신자 = 문서 오너
        assertThat(saved.getType()).isEqualTo("COMMENT_CREATED");
        assertThat(saved.getActorUserId()).isEqualTo(8L);
        assertThat(saved.getActorNickname()).isNull();
        assertThat(saved.getModelId()).isEqualTo(501L);
        assertThat(saved.getModelName()).isEqualTo("주문 ERD"); // 이벤트 시점 스냅샷
        assertThat(saved.getReadAt()).isNull();
    }

    @Test
    @DisplayName("게스트 원댓글도 오너에게 COMMENT_CREATED — actor_user_id 없이 별명 스냅샷만")
    void guestCommentNotifiesOwnerWithNickname() {
        recorder.notifyCommentCreated(model(7L), null, "지나가던 DBA");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationWriter).should().insert(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getActorUserId()).isNull();
        assertThat(saved.getActorNickname()).isEqualTo("지나가던 DBA");
    }

    @Test
    @DisplayName("오너의 자기 문서 댓글·좋아요는 알림 없음 — receiver==actor 스킵")
    void selfActionIsSkipped() {
        recorder.notifyCommentCreated(model(7L), 7L, null);
        recorder.notifyReactionAdded(model(7L), 7L);

        then(notificationWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("오너 부재(createdBy null, 방어) 문서는 알림 없음")
    void missingOwnerIsSkipped() {
        recorder.notifyCommentCreated(model(null), 8L, null);

        then(notificationWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("타인의 좋아요는 오너에게 REACTION_ADDED — 기존 알림이 없을 때만")
    void reactionInsertsWhenNotSuppressed() {
        Model model = model(7L);
        given(notificationRepository.existsByUserIdAndTypeAndActorUserIdAndModelId(
                7L, "REACTION_ADDED", 8L, 501L)).willReturn(false);

        recorder.notifyReactionAdded(model, 8L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationWriter).should().insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("REACTION_ADDED");
        assertThat(captor.getValue().getActorUserId()).isEqualTo(8L);
    }

    @Test
    @DisplayName("좋아요 재토글(on→off→on)은 억제 — 같은 수신자·행위자·문서의 기존 REACTION_ADDED가 있으면 미삽입(읽음 여부 무관)")
    void reactionRetoggleIsSuppressed() {
        given(notificationRepository.existsByUserIdAndTypeAndActorUserIdAndModelId(
                7L, "REACTION_ADDED", 8L, 501L)).willReturn(true);

        recorder.notifyReactionAdded(model(7L), 8L);

        then(notificationWriter).should(never()).insert(any(Notification.class));
    }

    @Test
    @DisplayName("오너 답글은 원댓글 회원 작성자에게 OWNER_REPLIED — actor=답글 작성자")
    void ownerReplyNotifiesCommentAuthor() {
        ModelComment parent = memberComment(31L, 8L);

        recorder.notifyOwnerReplied(model(7L), parent, 7L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationWriter).should().insert(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(8L);          // 수신자 = 원댓글 작성자
        assertThat(saved.getType()).isEqualTo("OWNER_REPLIED");
        assertThat(saved.getActorUserId()).isEqualTo(7L);     // 오너(답글 작성자)
    }

    @Test
    @DisplayName("게스트 원댓글에 오너 답글은 알림 없음 — 수신 불가(회원만), 자기 원댓글에 자기 답글도 없음")
    void ownerReplyToGuestOrSelfIsSkipped() {
        ModelComment guest = guestComment(31L);
        ModelComment own = memberComment(32L, 7L);

        recorder.notifyOwnerReplied(model(7L), guest, 7L);
        recorder.notifyOwnerReplied(model(7L), own, 7L);

        then(notificationWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("발행 실패는 본류에 무영향 — exists·insert 예외를 삼키고 warn만 남긴다(트랜잭션 경계 밖 catch)")
    void writerFailureDoesNotPropagate() {
        Model model = model(7L);
        willThrow(new IllegalStateException("insert fail"))
                .given(notificationWriter).insert(any(Notification.class));
        willThrow(new IllegalStateException("exists fail"))
                .given(notificationRepository).existsByUserIdAndTypeAndActorUserIdAndModelId(
                        anyLong(), eq("REACTION_ADDED"), anyLong(), anyLong());

        assertThatCode(() -> recorder.notifyCommentCreated(model, 8L, null)).doesNotThrowAnyException();
        assertThatCode(() -> recorder.notifyReactionAdded(model, 8L)).doesNotThrowAnyException();
        assertThatCode(() -> recorder.notifyOwnerReplied(model, memberComment(31L, 8L), 7L))
                .doesNotThrowAnyException();
    }

    // --- 픽스처

    private static Model model(Long createdBy) {
        Model model = new Model(77L, "주문 ERD", null, "postgresql", "{}", createdBy);
        ReflectionTestUtils.setField(model, "id", 501L);
        return model;
    }

    private static ModelComment memberComment(long id, long authorUserId) {
        ModelComment comment = new ModelComment(501L, null, "원댓글", authorUserId);
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }

    private static ModelComment guestComment(long id) {
        ModelComment comment = new ModelComment(501L, "방문자", "원댓글", "pbkdf2-sha256$hash");
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }
}
