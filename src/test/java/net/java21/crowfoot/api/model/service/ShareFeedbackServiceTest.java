package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.domain.ModelShareComment;
import net.java21.crowfoot.api.model.dto.CreateOwnerShareCommentRequest;
import net.java21.crowfoot.api.model.dto.CreateShareCommentRequest;
import net.java21.crowfoot.api.model.dto.ShareCommentResponse;
import net.java21.crowfoot.api.model.dto.ShareFeedbackResponse;
import net.java21.crowfoot.api.model.dto.ShareReactionResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareCommentQueryRepository.CommentRow;
import net.java21.crowfoot.api.model.repository.ModelShareCommentRepository;
import net.java21.crowfoot.api.model.repository.ModelShareReactionRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * 공유 문서 피드백 단위 테스트 (08-core/02-model.md Section 1.10.6·1.10.7) —
 * 반응 토글(원자 판정 분기·카운터 ±1·감사 actor null)·피드백 초기화(reacted·작성자명 조인)·
 * 익명 댓글(등록·본인 삭제 −(1+답글)·403 조건)·오너 답글(1단계 제한·AdminGuard 위임·관리 삭제)·
 * 토큰 판정(404/410)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ShareFeedbackServiceTest {

    private static final Instant PAST = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";

    @Mock
    private ModelShareRepository shareRepository;
    @Mock
    private ModelShareReactionRepository reactionRepository;
    @Mock
    private ModelShareCommentRepository commentRepository;
    @Mock
    private net.java21.crowfoot.api.model.repository.ModelShareCommentQueryRepository commentQueryRepository;
    @Mock
    private ModelRepository modelRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditRecorder auditRecorder;
    @InjectMocks
    private ShareFeedbackService feedbackService;

    // --- 반응 토글 (1.10.6)

    @Test
    @DisplayName("토글은 삽입에 성공하면 ON이다 — 원자 +1, 응답은 스냅숏+1, 감사 actor는 null(익명)")
    void toggleReactionAdds() {
        ModelShare share = activeShare();
        given(shareRepository.findByShareToken("tok123")).willReturn(Optional.of(share));
        given(reactionRepository.insertIgnoreConflict(9L, ACTOR)).willReturn(1);

        ShareReactionResponse response = feedbackService.toggleReaction("tok123", ACTOR);

        assertThat(response.reactionCount()).isEqualTo(8L); // 7 + 1
        assertThat(response.reacted()).isTrue();
        then(shareRepository).should().addReactionCount(9L, 1L);
        then(reactionRepository).should(never()).deleteByShareIdAndVisitorKey(anyLong(), anyString());
        then(auditRecorder).should().record(null, "SHARE_REACTION_TOGGLED", "SHARE", "9",
                Map.of("added", "true"));
    }

    @Test
    @DisplayName("토글은 이미 반응한 방문자(충돌)면 제거로 전환한다 — 멱등 OFF, 원자 -1")
    void toggleReactionRemovesOnConflict() {
        ModelShare share = activeShare();
        given(shareRepository.findByShareToken("tok123")).willReturn(Optional.of(share));
        given(reactionRepository.insertIgnoreConflict(9L, ACTOR)).willReturn(0);

        ShareReactionResponse response = feedbackService.toggleReaction("tok123", ACTOR);

        assertThat(response.reactionCount()).isEqualTo(6L); // 7 - 1
        assertThat(response.reacted()).isFalse();
        then(reactionRepository).should().deleteByShareIdAndVisitorKey(9L, ACTOR);
        then(shareRepository).should().addReactionCount(9L, -1L);
        then(auditRecorder).should().record(null, "SHARE_REACTION_TOGGLED", "SHARE", "9",
                Map.of("added", "false"));
    }

    // --- 피드백 초기화 (1.10.7)

    @Test
    @DisplayName("피드백 초기화는 반응 상태와 댓글 목록을 한 몸으로 내려준다 — 오너 답글은 작성자명·owner=true")
    void listFeedbackMapsCommentsAndReacted() {
        ModelShare share = activeShare();
        given(shareRepository.findByShareToken("tok123")).willReturn(Optional.of(share));
        given(reactionRepository.existsByShareIdAndVisitorKey(9L, ACTOR)).willReturn(true);
        given(commentQueryRepository.findByShareIdOrderByIdAsc(9L)).willReturn(List.of(
                new CommentRow(31L, 9L, null, "방문자", "구조 좋네요", null, null,
                        Instant.parse("2026-09-20T10:00:00Z")),
                new CommentRow(32L, 9L, 31L, null, "감사합니다", 7L, "오너",
                        Instant.parse("2026-09-20T10:05:00Z"))));

        ShareFeedbackResponse response = feedbackService.listFeedback("tok123", ACTOR);

        assertThat(response.reactionCount()).isEqualTo(7L);
        assertThat(response.reacted()).isTrue();
        assertThat(response.comments()).hasSize(2);
        assertThat(response.comments().get(0).nickname()).isEqualTo("방문자"); // 익명 = 저장된 별명
        assertThat(response.comments().get(0).owner()).isFalse();
        assertThat(response.comments().get(0).parentCommentId()).isNull();
        assertThat(response.comments().get(1).nickname()).isEqualTo("오너"); // 오너 답글 = users.name
        assertThat(response.comments().get(1).owner()).isTrue();
        assertThat(response.comments().get(1).parentCommentId()).isEqualTo("31");
    }

    @Test
    @DisplayName("쿠키 없는 첫 방문자는 reacted=false다 — 존재 판정을 굳이 조회하지 않는다")
    void listFeedbackDefaultsToNotReactedWithoutCookie() {
        given(shareRepository.findByShareToken("tok123")).willReturn(Optional.of(activeShare()));
        given(commentQueryRepository.findByShareIdOrderByIdAsc(9L)).willReturn(List.of());

        ShareFeedbackResponse response = feedbackService.listFeedback("tok123", null);

        assertThat(response.reacted()).isFalse();
        assertThat(response.comments()).isEmpty();
        then(reactionRepository).should(never()).existsByShareIdAndVisitorKey(anyLong(), anyString());
    }

    // --- 익명 댓글 (1.10.7)

    @Test
    @DisplayName("익명 댓글은 별명·내용·visitor_key로 저장하고 카운터 +1, 감사 actor는 null이다")
    void createCommentSavesAnonymousShape() {
        given(shareRepository.findByShareToken("tok123")).willReturn(Optional.of(activeShare()));
        given(commentRepository.save(any(ModelShareComment.class))).willAnswer(inv -> {
            ModelShareComment comment = inv.getArgument(0);
            ReflectionTestUtils.setField(comment, "id", 31L);
            return comment;
        });

        ShareCommentResponse response = feedbackService.createComment("tok123", ACTOR,
                new CreateShareCommentRequest("방문자", "구조 좋네요"));

        assertThat(response.commentId()).isEqualTo("31");
        assertThat(response.owner()).isFalse();
        ArgumentCaptor<ModelShareComment> captor = ArgumentCaptor.forClass(ModelShareComment.class);
        then(commentRepository).should().save(captor.capture());
        assertThat(captor.getValue().getShareId()).isEqualTo(9L);
        assertThat(captor.getValue().getParentCommentId()).isNull();
        assertThat(captor.getValue().getNickname()).isEqualTo("방문자");
        assertThat(captor.getValue().getVisitorKey()).isEqualTo(ACTOR);
        assertThat(captor.getValue().getAuthorUserId()).isNull();
        then(shareRepository).should().addCommentCount(9L, 1L);
        then(auditRecorder).should().record(null, "SHARE_COMMENT_CREATED", "SHARE_COMMENT", "31",
                Map.of("shareId", "9", "anonymous", "true"));
    }

    @Test
    @DisplayName("본인 원댓글 삭제는 답글 수만큼 동반해서 카운터를 내린다 — CASCADE와 1+답글의 짝")
    void deleteCommentSubtractsRepliesToo() {
        ModelShare share = activeShare();
        given(shareRepository.findByShareToken("tok123")).willReturn(Optional.of(share));
        ModelShareComment comment = anonymousComment(31L, null, ACTOR);
        given(commentRepository.findByIdAndShareId(31L, 9L)).willReturn(Optional.of(comment));
        given(commentRepository.countByParentCommentId(31L)).willReturn(2L);

        feedbackService.deleteComment("tok123", 31L, ACTOR);

        then(commentRepository).should().delete(comment);
        then(shareRepository).should().addCommentCount(9L, -3L); // 1 + 답글 2
        then(auditRecorder).should().record(null, "SHARE_COMMENT_DELETED", "SHARE_COMMENT", "31",
                Map.of("shareId", "9", "withReplies", "2"));
    }

    @Test
    @DisplayName("익명 삭제는 오너 댓글·쿠키 불일치·쿠키 없음이면 403이다 — 서버가 최종 판정")
    void deleteCommentRejectsNonAuthors() {
        ModelShare share = activeShare();
        given(shareRepository.findByShareToken("tok123")).willReturn(Optional.of(share));
        given(commentRepository.findByIdAndShareId(32L, 9L))
                .willReturn(Optional.of(ownerComment(32L, 31L))); // 오너 답글
        given(commentRepository.findByIdAndShareId(31L, 9L))
                .willReturn(Optional.of(anonymousComment(31L, null, "another-visitor")));

        for (Runnable call : List.<Runnable>of(
                () -> feedbackService.deleteComment("tok123", 32L, ACTOR),      // 오너 댓글
                () -> feedbackService.deleteComment("tok123", 31L, "22222222-2222-2222-2222-222222222222"), // 타인
                () -> feedbackService.deleteComment("tok123", 31L, null))) {    // 쿠키 없음
            assertThatThrownBy(call::run)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PERMISSION_DENIED);
        }
        then(commentRepository).should(never()).delete(any(ModelShareComment.class));
        then(shareRepository).should(never()).addCommentCount(anyLong(), anyLong());
    }

    // --- 오너 답글·관리 (1.10.7)

    @Test
    @DisplayName("오너 답글은 문서 작성자 기준으로 저장한다 — nickname=작성자명, owner=true, 감사 actor=본인")
    void createOwnerCommentSavesReply() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L))); // createdBy=7
        ModelShare share = activeShare();
        given(shareRepository.findByIdAndModelId(9L, 501L)).willReturn(Optional.of(share));
        given(commentRepository.findByIdAndShareId(31L, 9L))
                .willReturn(Optional.of(anonymousComment(31L, null, ACTOR)));
        given(commentRepository.save(any(ModelShareComment.class))).willAnswer(inv -> {
            ModelShareComment comment = inv.getArgument(0);
            ReflectionTestUtils.setField(comment, "id", 32L);
            return comment;
        });
        User owner = org.mockito.Mockito.mock(User.class);
        given(owner.getName()).willReturn("오너");
        given(userRepository.findById(7L)).willReturn(Optional.of(owner));

        ShareCommentResponse response = feedbackService.createOwnerComment(7L, 77L, 501L, 9L,
                new CreateOwnerShareCommentRequest(31L, "감사합니다"));

        assertThat(response.commentId()).isEqualTo("32");
        assertThat(response.parentCommentId()).isEqualTo("31");
        assertThat(response.nickname()).isEqualTo("오너");
        assertThat(response.owner()).isTrue();
        ArgumentCaptor<ModelShareComment> captor = ArgumentCaptor.forClass(ModelShareComment.class);
        then(commentRepository).should().save(captor.capture());
        assertThat(captor.getValue().getAuthorUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getVisitorKey()).isNull(); // 오너 형태 불변식
        then(shareRepository).should().addCommentCount(9L, 1L);
        then(adminGuard).should(never()).requireAdmin(anyLong()); // 작성자 본인이라 관리자 판정 불필요
        then(auditRecorder).should().record(7L, "SHARE_COMMENT_CREATED", "SHARE_COMMENT", "32",
                Map.of("shareId", "9", "anonymous", "false", "parentCommentId", "31"));
    }

    @Test
    @DisplayName("오너 답글의 부모가 답글이거나 그 링크의 댓글이 아니면 400 detail.share.comment.parent다")
    void createOwnerCommentRejectsInvalidParent() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model(7L)));
        ModelShare share = activeShare();
        given(shareRepository.findByIdAndModelId(9L, 501L)).willReturn(Optional.of(share));
        given(commentRepository.findByIdAndShareId(31L, 9L))
                .willReturn(Optional.of(ownerComment(31L, 30L))); // 부모가 이미 답글(2단계)

        assertThatThrownBy(() -> feedbackService.createOwnerComment(7L, 77L, 501L, 9L,
                new CreateOwnerShareCommentRequest(31L, "2단계 답글")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getMessageKey())
                .isEqualTo("detail.share.comment.parent");

        given(commentRepository.findByIdAndShareId(31L, 9L)).willReturn(Optional.empty()); // 타 공유 댓글
        assertThatThrownBy(() -> feedbackService.createOwnerComment(7L, 77L, 501L, 9L,
                new CreateOwnerShareCommentRequest(31L, "답글")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getMessageKey())
                .isEqualTo("detail.share.comment.parent");
        then(commentRepository).should(never()).save(any(ModelShareComment.class));
    }

    @Test
    @DisplayName("작성자가 아닌 오너 경로 접근은 AdminGuard로 넘어간다 — 관리자 아니면 403")
    void createOwnerCommentDelegatesToAdminGuard() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model(7L)));
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .given(adminGuard).requireAdmin(8L);

        assertThatThrownBy(() -> feedbackService.createOwnerComment(8L, 77L, 501L, 9L,
                new CreateOwnerShareCommentRequest(31L, "타인 답글")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        then(commentRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("오너 관리 삭제는 그 링크의 모든 댓글을 지운다 — 익명 댓글도, 카운터는 1+답글만큼")
    void deleteOwnerCommentRemovesAnyComment() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model(7L)));
        ModelShare share = activeShare();
        given(shareRepository.findByIdAndModelId(9L, 501L)).willReturn(Optional.of(share));
        ModelShareComment comment = anonymousComment(31L, null, "someone");
        given(commentRepository.findByIdAndShareId(31L, 9L)).willReturn(Optional.of(comment));
        given(commentRepository.countByParentCommentId(31L)).willReturn(1L);

        feedbackService.deleteOwnerComment(7L, 77L, 501L, 9L, 31L);

        then(commentRepository).should().delete(comment);
        then(shareRepository).should().addCommentCount(9L, -2L);
        then(auditRecorder).should().record(7L, "SHARE_COMMENT_DELETED", "SHARE_COMMENT", "31",
                Map.of("shareId", "9", "withReplies", "1"));
    }

    @Test
    @DisplayName("오너 관리 경로의 댓글이 없으면 404 SHARE_COMMENT_NOT_FOUND다")
    void deleteOwnerCommentRejectsUnknownComment() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model(7L)));
        given(shareRepository.findByIdAndModelId(9L, 501L)).willReturn(Optional.of(activeShare()));
        given(commentRepository.findByIdAndShareId(31L, 9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> feedbackService.deleteOwnerComment(7L, 77L, 501L, 9L, 31L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHARE_COMMENT_NOT_FOUND);
    }

    // --- 토큰 판정 (공개 조회와 같은 규칙)

    @Test
    @DisplayName("피드백 경로도 같은 토큰 판정 — 없으면 404 SHARE_NOT_FOUND, 기간 밖이면 410 SHARE_INACTIVE")
    void feedbackRejectsUnknownOrInactiveToken() {
        given(shareRepository.findByShareToken("nope")).willReturn(Optional.empty());
        ModelShare expired = new ModelShare(501L, "expired", null, PAST, 7L);
        ReflectionTestUtils.setField(expired, "id", 9L);
        given(shareRepository.findByShareToken("expired")).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> feedbackService.listFeedback("nope", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHARE_NOT_FOUND);
        assertThatThrownBy(() -> feedbackService.toggleReaction("expired", ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHARE_INACTIVE);
        then(commentQueryRepository).shouldHaveNoInteractions();
        then(reactionRepository).shouldHaveNoInteractions();
    }

    // --- 픽스처

    /** 활성 공유 id=9, reactionCount=7 */
    private static ModelShare activeShare() {
        ModelShare share = new ModelShare(501L, "tok123", PAST, FUTURE, 7L);
        ReflectionTestUtils.setField(share, "id", 9L);
        share.setReactionCount(7L);
        return share;
    }

    private static Model model(long createdBy) {
        return new Model(77L, "주문 ERD", null, "postgresql", "{}", createdBy);
    }

    private static ModelShareComment anonymousComment(long id, Long parentCommentId, String visitorKey) {
        ModelShareComment comment =
                new ModelShareComment(9L, parentCommentId, "방문자", "구조 좋네요", visitorKey);
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }

    private static ModelShareComment ownerComment(long id, Long parentCommentId) {
        ModelShareComment comment = new ModelShareComment(9L, parentCommentId, "감사합니다", 7L);
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }
}
