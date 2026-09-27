package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelComment;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.dto.CreateModelCommentRequest;
import net.java21.crowfoot.api.model.dto.CreateShareCommentRequest;
import net.java21.crowfoot.api.model.dto.ShareCommentResponse;
import net.java21.crowfoot.api.model.dto.ShareFeedbackResponse;
import net.java21.crowfoot.api.model.dto.ShareReactionResponse;
import net.java21.crowfoot.api.model.dto.UpdateShareCommentRequest;
import net.java21.crowfoot.api.model.repository.ModelCommentQueryRepository;
import net.java21.crowfoot.api.model.repository.ModelCommentQueryRepository.CommentRow;
import net.java21.crowfoot.api.model.repository.ModelCommentRepository;
import net.java21.crowfoot.api.model.repository.ModelReactionQueryRepository;
import net.java21.crowfoot.api.model.repository.ModelReactionRepository;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * 문서 피드백 단위 테스트 (08-core/02-model.md Section 1.10.6·1.10.7) — 스레드는 문서(model) 단위다
 * (2026-09-28 재설계). 토큰 경로(토큰→문서 해석 뒤 문서 카운터·스레드)와 멤버 문서 경로(역할 게이트)를
 * 함께 검증한다: 반응 토글(원자 판정 분기·카운터 ±1·감사 actor=회원)·피드백 초기화(reacted·authorType)·
 * 댓글(회원 계정 판정·비회원 비밀번호 판정 — 실물 GuestPasswordHasher로 해시·검증)·오너 답글
 * (1단계 제한·AdminGuard 위임·관리 삭제 — 멤버 경로)·토큰 판정(404/410)·내 피드백 역조회(1.10.9).
 */
@ExtendWith(MockitoExtension.class)
class ShareFeedbackServiceTest {

    private static final Instant PAST = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final String GUEST_PASSWORD = "pass1234";

    @Mock
    private ModelShareRepository shareRepository;
    @Mock
    private ModelReactionRepository reactionRepository;
    @Mock
    private ModelCommentRepository commentRepository;
    @Mock
    private ModelCommentQueryRepository commentQueryRepository;
    @Mock
    private ModelReactionQueryRepository reactionQueryRepository;
    @Mock
    private ModelRepository modelRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditRecorder auditRecorder;

    private final GuestPasswordHasher passwordHasher = new GuestPasswordHasher();
    private ShareFeedbackService feedbackService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        feedbackService = new ShareFeedbackService(shareRepository, reactionRepository, commentRepository,
                commentQueryRepository, reactionQueryRepository, modelRepository, userRepository,
                roleChecker, passwordHasher, adminGuard, auditRecorder);
    }

    // --- 반응 토글 (1.10.6 — 회원전용, 문서 단위)

    @Test
    @DisplayName("토글은 삽입에 성공하면 ON이다 — 원자 +1, 응답은 스냅숏+1, 감사는 MODEL 단위로 modelId를 찍는다")
    void toggleReactionAdds() {
        givenActiveModel();
        given(reactionRepository.insertIgnoreConflict(501L, 7L)).willReturn(1);

        ShareReactionResponse response = feedbackService.toggleReaction("tok123", 7L);

        assertThat(response.reactionCount()).isEqualTo(8L); // 7 + 1
        assertThat(response.reacted()).isTrue();
        then(modelRepository).should().addReactionCount(501L, 1L);
        then(reactionRepository).should(never()).deleteByModelIdAndUserId(anyLong(), anyLong());
        then(auditRecorder).should().record(7L, "MODEL_REACTION_TOGGLED", "MODEL", "501",
                Map.of("added", "true"));
    }

    @Test
    @DisplayName("토글은 이미 반응한 회원(충돌)이면 제거로 전환한다 — 멱등 OFF, 원자 -1")
    void toggleReactionRemovesOnConflict() {
        givenActiveModel();
        given(reactionRepository.insertIgnoreConflict(501L, 7L)).willReturn(0);

        ShareReactionResponse response = feedbackService.toggleReaction("tok123", 7L);

        assertThat(response.reactionCount()).isEqualTo(6L); // 7 - 1
        assertThat(response.reacted()).isFalse();
        then(reactionRepository).should().deleteByModelIdAndUserId(501L, 7L);
        then(modelRepository).should().addReactionCount(501L, -1L);
        then(auditRecorder).should().record(7L, "MODEL_REACTION_TOGGLED", "MODEL", "501",
                Map.of("added", "false"));
    }

    // --- 피드백 초기화 (1.10.7 — 선택 인증)

    @Test
    @DisplayName("피드백 초기화는 반응 상태와 댓글 목록을 한 몸으로 내려준다 — authorType은 model.createdBy 비교로 결정")
    void listFeedbackMapsCommentsAndReacted() {
        givenActiveModel();
        given(reactionRepository.existsByModelIdAndUserId(501L, 7L)).willReturn(true);
        Instant created = Instant.parse("2026-09-20T10:00:00Z");
        given(commentQueryRepository.findByModelIdOrderByIdAsc(501L)).willReturn(List.of(
                new CommentRow(31L, 501L, null, "방문자", "구조 좋네요", null, null, created, created),
                new CommentRow(32L, 501L, 31L, null, "감사합니다", 7L, "오너", created, created),
                new CommentRow(33L, 501L, null, null, "회원도 답니다", 8L, "다른회원", created,
                        Instant.parse("2026-09-20T11:00:00Z"))));

        ShareFeedbackResponse response = feedbackService.listFeedback("tok123", 7L);

        assertThat(response.reactionCount()).isEqualTo(7L); // models.reaction_count(문서 단위)
        assertThat(response.reacted()).isTrue();
        assertThat(response.comments()).hasSize(3);
        assertThat(response.comments().get(0).nickname()).isEqualTo("방문자");   // 비회원 = 저장된 별명
        assertThat(response.comments().get(0).authorType()).isEqualTo("guest");
        assertThat(response.comments().get(1).nickname()).isEqualTo("오너");     // 회원 = users.name
        assertThat(response.comments().get(1).authorType()).isEqualTo("owner");  // 작성자 본인
        assertThat(response.comments().get(1).parentCommentId()).isEqualTo("31");
        assertThat(response.comments().get(2).authorType()).isEqualTo("member"); // 그 외 회원
        assertThat(response.comments().get(2).edited()).isTrue();                // updated_at ≠ created_at
        assertThat(response.comments().get(1).edited()).isFalse();
    }

    @Test
    @DisplayName("비회원 요청(신원 없음)은 reacted=false다 — 존재 판정을 굳이 조회하지 않는다")
    void listFeedbackDefaultsToNotReactedForGuest() {
        givenActiveModel();
        given(commentQueryRepository.findByModelIdOrderByIdAsc(501L)).willReturn(List.of());

        ShareFeedbackResponse response = feedbackService.listFeedback("tok123", null);

        assertThat(response.reacted()).isFalse();
        assertThat(response.comments()).isEmpty();
        then(reactionRepository).should(never()).existsByModelIdAndUserId(anyLong(), anyLong());
    }

    // --- 댓글 등록 — 토큰 경로 (1.10.7 — 회원/비회원 같은 경로)

    @Test
    @DisplayName("회원 댓글은 계정으로 저장한다 — nickname·password는 무시, authorType=member, 감사 actor=본인")
    void createCommentSavesMemberShape() {
        givenActiveModel(); // 오너=7, 작성자=8 → member
        given(commentRepository.save(any(ModelComment.class))).willAnswer(inv -> {
            ModelComment comment = inv.getArgument(0);
            ReflectionTestUtils.setField(comment, "id", 31L);
            return comment;
        });
        User author = org.mockito.Mockito.mock(User.class);
        given(author.getName()).willReturn("다른회원");
        given(userRepository.findById(8L)).willReturn(Optional.of(author));

        ShareCommentResponse response = feedbackService.createComment("tok123", 8L,
                new CreateShareCommentRequest("무시되는별명", "회원 댓글", "ignored99"));

        assertThat(response.commentId()).isEqualTo("31");
        assertThat(response.authorType()).isEqualTo("member");
        assertThat(response.nickname()).isEqualTo("다른회원");
        ArgumentCaptor<ModelComment> captor = ArgumentCaptor.forClass(ModelComment.class);
        then(commentRepository).should().save(captor.capture());
        assertThat(captor.getValue().getModelId()).isEqualTo(501L); // 문서 단위 앵커
        assertThat(captor.getValue().getParentCommentId()).isNull();
        assertThat(captor.getValue().getAuthorUserId()).isEqualTo(8L);
        assertThat(captor.getValue().getNickname()).isNull();      // 회원 형태 불변식
        assertThat(captor.getValue().getPasswordHash()).isNull();
        then(modelRepository).should().addCommentCount(501L, 1L);
        then(auditRecorder).should().record(8L, "MODEL_COMMENT_CREATED", "MODEL_COMMENT", "31",
                Map.of("modelId", "501", "authorType", "member"));
    }

    @Test
    @DisplayName("비회원 댓글은 별명·PBKDF2 해시로 저장한다 — 평문 비밀번호는 남지 않는다, 감사 actor=null")
    void createCommentSavesGuestShape() {
        givenActiveModel();
        given(commentRepository.save(any(ModelComment.class))).willAnswer(inv -> {
            ModelComment comment = inv.getArgument(0);
            ReflectionTestUtils.setField(comment, "id", 31L);
            return comment;
        });

        ShareCommentResponse response = feedbackService.createComment("tok123", null,
                new CreateShareCommentRequest("방문자", "구조 좋네요", GUEST_PASSWORD));

        assertThat(response.commentId()).isEqualTo("31");
        assertThat(response.authorType()).isEqualTo("guest");
        assertThat(response.nickname()).isEqualTo("방문자");
        ArgumentCaptor<ModelComment> captor = ArgumentCaptor.forClass(ModelComment.class);
        then(commentRepository).should().save(captor.capture());
        assertThat(captor.getValue().getModelId()).isEqualTo(501L);
        assertThat(captor.getValue().getNickname()).isEqualTo("방문자");
        assertThat(captor.getValue().getAuthorUserId()).isNull();
        assertThat(captor.getValue().getPasswordHash())
                .isNotBlank()
                .isNotEqualTo(GUEST_PASSWORD)
                .startsWith("pbkdf2-sha256$"); // 자기서술형 해시
        assertThat(passwordHasher.matches(GUEST_PASSWORD, captor.getValue().getPasswordHash())).isTrue();
        then(auditRecorder).should().record(null, "MODEL_COMMENT_CREATED", "MODEL_COMMENT", "31",
                Map.of("modelId", "501", "authorType", "guest"));
    }

    @Test
    @DisplayName("비회원 등록은 별명·비밀번호가 없으면 400 detail.share.comment.guest-required다")
    void createCommentRejectsIncompleteGuest() {
        givenActiveModel();

        for (CreateShareCommentRequest request : List.of(
                new CreateShareCommentRequest(null, "내용", GUEST_PASSWORD),   // 별명 없음
                new CreateShareCommentRequest("방문자", "내용", null))) {       // 비밀번호 없음
            assertThatThrownBy(() -> feedbackService.createComment("tok123", null, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getMessageKey())
                    .isEqualTo("detail.share.comment.guest-required");
        }
        then(commentRepository).should(never()).save(any(ModelComment.class));
    }

    // --- 댓글 수정·삭제 — 토큰 경로 (1.10.7 — 회원 계정 판정 / 비회원 비밀번호 판정)

    @Test
    @DisplayName("회원은 본인 댓글을 고친다 — 내용만 바뀌고 응답 edited=true, 감사 actor=본인")
    void updateCommentByAuthor() {
        givenActiveModel();
        ModelComment comment = memberComment(31L, null, 8L);
        given(commentRepository.findByIdAndModelId(31L, 501L)).willReturn(Optional.of(comment));
        given(commentRepository.save(any(ModelComment.class))).willAnswer(inv -> inv.getArgument(0));

        ShareCommentResponse response = feedbackService.updateComment("tok123", 31L, 8L,
                new UpdateShareCommentRequest("고친 내용", null));

        assertThat(response.content()).isEqualTo("고친 내용");
        assertThat(response.edited()).isTrue();
        then(auditRecorder).should().record(8L, "MODEL_COMMENT_UPDATED", "MODEL_COMMENT", "31",
                Map.of("modelId", "501"));
    }

    @Test
    @DisplayName("비회원은 비밀번호가 일치할 때만 고친다 — 일치하지 않으면 403 detail.share.comment.password")
    void updateCommentByGuestPassword() {
        givenActiveModel();
        given(commentRepository.findByIdAndModelId(31L, 501L))
                .willReturn(Optional.of(guestComment(31L, GUEST_PASSWORD)));
        given(commentRepository.save(any(ModelComment.class))).willAnswer(inv -> inv.getArgument(0));

        ShareCommentResponse response = feedbackService.updateComment("tok123", 31L, null,
                new UpdateShareCommentRequest("고친 내용", GUEST_PASSWORD));
        assertThat(response.content()).isEqualTo("고친 내용");

        assertThatThrownBy(() -> feedbackService.updateComment("tok123", 31L, null,
                new UpdateShareCommentRequest("타인 수정", "wrong-pass")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getMessageKey())
                .isEqualTo("detail.share.comment.password");
        assertThatThrownBy(() -> feedbackService.updateComment("tok123", 31L, null,
                new UpdateShareCommentRequest("비번 없음", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("회원 댓글은 다른 회원·비회원이 고칠 수 없다 — 계정 판정 403")
    void updateCommentRejectsNonAuthor() {
        givenActiveModel();
        given(commentRepository.findByIdAndModelId(31L, 501L))
                .willReturn(Optional.of(memberComment(31L, null, 8L)));

        assertThatThrownBy(() -> feedbackService.updateComment("tok123", 31L, 9L,
                new UpdateShareCommentRequest("타인 수정", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThatThrownBy(() -> feedbackService.updateComment("tok123", 31L, null,
                new UpdateShareCommentRequest("비회원 수정", GUEST_PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("본인 원댓글 삭제는 답글 수만큼 동반해서 카운터를 내린다 — CASCADE와 1+답글의 짝")
    void deleteCommentSubtractsRepliesToo() {
        givenActiveModel();
        ModelComment comment = guestComment(31L, GUEST_PASSWORD);
        given(commentRepository.findByIdAndModelId(31L, 501L)).willReturn(Optional.of(comment));
        given(commentRepository.countByParentCommentId(31L)).willReturn(2L);

        feedbackService.deleteComment("tok123", 31L, null, GUEST_PASSWORD);

        then(commentRepository).should().delete(comment);
        then(modelRepository).should().addCommentCount(501L, -3L); // 1 + 답글 2
        then(auditRecorder).should().record(null, "MODEL_COMMENT_DELETED", "MODEL_COMMENT", "31",
                Map.of("modelId", "501", "withReplies", "2"));
    }

    @Test
    @DisplayName("삭제 판정 — 회원 댓글은 본인 계정만, 비회원 댓글은 비밀번호 일치만. 아니면 403")
    void deleteCommentRejectsNonAuthors() {
        givenActiveModel();
        ModelComment ownerReply = memberComment(32L, 31L, 7L);
        ModelComment guestComment = guestComment(31L, GUEST_PASSWORD);
        given(commentRepository.findByIdAndModelId(32L, 501L)).willReturn(Optional.of(ownerReply));
        given(commentRepository.findByIdAndModelId(31L, 501L)).willReturn(Optional.of(guestComment));

        for (Runnable call : List.<Runnable>of(
                () -> feedbackService.deleteComment("tok123", 32L, 8L, null),          // 타인 회원 댓글
                () -> feedbackService.deleteComment("tok123", 31L, null, "wrong-pass"), // 비밀번호 불일치
                () -> feedbackService.deleteComment("tok123", 31L, null, null))) {      // 비밀번호 없음
            assertThatThrownBy(call::run)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PERMISSION_DENIED);
        }
        then(commentRepository).should(never()).delete(any(ModelComment.class));
        then(modelRepository).should(never()).addCommentCount(anyLong(), anyLong());
    }

    // --- 멤버 문서 경로 (1.10.7 — 문서 열기 댓글 탭)

    @Test
    @DisplayName("멤버 피드백 초기화는 역할 무관 — 토큰 경로와 같은 형태의 문서 스레드")
    void listModelFeedbackReturnsThread() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L)));
        given(reactionRepository.existsByModelIdAndUserId(501L, 7L)).willReturn(true);
        given(commentQueryRepository.findByModelIdOrderByIdAsc(501L)).willReturn(List.of());

        ShareFeedbackResponse response = feedbackService.listModelFeedback(7L, 77L, 501L);

        assertThat(response.reactionCount()).isEqualTo(7L);
        assertThat(response.reacted()).isTrue();
        then(roleChecker).should().requireMember(7L, 77L); // 읽기·반응은 멤버면 역할 무관
        then(roleChecker).should(never()).requireCommenter(anyLong(), anyLong());
    }

    @Test
    @DisplayName("비멤버의 문서 피드백 접근은 존재 은닉 404 WORKSPACE_NOT_FOUND다")
    void listModelFeedbackHidesNonMember() {
        willThrow(new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND))
                .given(roleChecker).requireMember(8L, 77L);

        assertThatThrownBy(() -> feedbackService.listModelFeedback(8L, 77L, 501L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND);
        then(modelRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("멤버 경로 반응 토글도 문서 단위로 같은 판정 — VIEWER도 좋아요는 가능")
    void toggleModelReactionForAnyMember() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L)));
        given(reactionRepository.insertIgnoreConflict(501L, 7L)).willReturn(1);

        ShareReactionResponse response = feedbackService.toggleModelReaction(7L, 77L, 501L);

        assertThat(response.reacted()).isTrue();
        assertThat(response.reactionCount()).isEqualTo(8L);
        then(modelRepository).should().addReactionCount(501L, 1L);
        then(roleChecker).should(never()).requireCommenter(anyLong(), anyLong());
        then(auditRecorder).should().record(7L, "MODEL_REACTION_TOGGLED", "MODEL", "501",
                Map.of("added", "true"));
    }

    @Test
    @DisplayName("멤버 댓글 등록은 Commenter 이상 게이트를 지난다 — Viewer(403)·비멤버(404)는 서비스 앞에서 막힌다")
    void createModelCommentRequiresCommenter() {
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .given(roleChecker).requireCommenter(10L, 77L); // VIEWER

        assertThatThrownBy(() -> feedbackService.createModelComment(10L, 77L, 501L,
                new CreateModelCommentRequest("뷰어 댓글", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        then(commentRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("멤버 원댓글은 계정으로 저장한다 — authorType=member(문서 작성자 본인이면 owner), 답글 없음")
    void createModelCommentSavesMemberTopLevel() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L))); // createdBy=7
        given(commentRepository.save(any(ModelComment.class))).willAnswer(inv -> {
            ModelComment comment = inv.getArgument(0);
            ReflectionTestUtils.setField(comment, "id", 33L);
            return comment;
        });
        User author = org.mockito.Mockito.mock(User.class);
        given(author.getName()).willReturn("다른회원");
        given(userRepository.findById(8L)).willReturn(Optional.of(author));

        ShareCommentResponse response = feedbackService.createModelComment(8L, 77L, 501L,
                new CreateModelCommentRequest("회원 댓글", null));

        assertThat(response.commentId()).isEqualTo("33");
        assertThat(response.authorType()).isEqualTo("member");
        assertThat(response.parentCommentId()).isNull();
        ArgumentCaptor<ModelComment> captor = ArgumentCaptor.forClass(ModelComment.class);
        then(commentRepository).should().save(captor.capture());
        assertThat(captor.getValue().getModelId()).isEqualTo(501L);
        assertThat(captor.getValue().getAuthorUserId()).isEqualTo(8L);
        then(modelRepository).should().addCommentCount(501L, 1L);
        then(auditRecorder).should().record(8L, "MODEL_COMMENT_CREATED", "MODEL_COMMENT", "33",
                Map.of("modelId", "501", "authorType", "member"));
    }

    @Test
    @DisplayName("오너 답글은 문서 작성자 기준으로 저장한다 — nickname=작성자명, authorType=owner, 감사에 parentCommentId")
    void createModelCommentSavesOwnerReply() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L))); // createdBy=7
        given(commentRepository.findByIdAndModelId(31L, 501L))
                .willReturn(Optional.of(guestComment(31L, GUEST_PASSWORD)));
        given(commentRepository.save(any(ModelComment.class))).willAnswer(inv -> {
            ModelComment comment = inv.getArgument(0);
            ReflectionTestUtils.setField(comment, "id", 32L);
            return comment;
        });
        User owner = org.mockito.Mockito.mock(User.class);
        given(owner.getName()).willReturn("오너");
        given(userRepository.findById(7L)).willReturn(Optional.of(owner));

        ShareCommentResponse response = feedbackService.createModelComment(7L, 77L, 501L,
                new CreateModelCommentRequest("감사합니다", 31L));

        assertThat(response.commentId()).isEqualTo("32");
        assertThat(response.parentCommentId()).isEqualTo("31");
        assertThat(response.nickname()).isEqualTo("오너");
        assertThat(response.authorType()).isEqualTo("owner");
        ArgumentCaptor<ModelComment> captor = ArgumentCaptor.forClass(ModelComment.class);
        then(commentRepository).should().save(captor.capture());
        assertThat(captor.getValue().getAuthorUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getNickname()).isNull();   // 회원 형태 불변식
        assertThat(captor.getValue().getPasswordHash()).isNull();
        then(modelRepository).should().addCommentCount(501L, 1L);
        then(adminGuard).should(never()).requireAdmin(anyLong()); // 작성자 본인이라 관리자 판정 불필요
        then(auditRecorder).should().record(7L, "MODEL_COMMENT_CREATED", "MODEL_COMMENT", "32",
                Map.of("modelId", "501", "authorType", "owner", "parentCommentId", "31"));
    }

    @Test
    @DisplayName("오너 답글의 부모가 답글이거나 그 문서의 댓글이 아니면 400 detail.share.comment.parent다")
    void createModelCommentRejectsInvalidParent() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L)));
        given(commentRepository.findByIdAndModelId(31L, 501L))
                .willReturn(Optional.of(memberComment(31L, 30L, 7L)))  // 부모가 이미 답글(2단계)
                .willReturn(Optional.empty());                          // 타 문서 댓글

        assertThatThrownBy(() -> feedbackService.createModelComment(7L, 77L, 501L,
                new CreateModelCommentRequest("2단계 답글", 31L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getMessageKey())
                .isEqualTo("detail.share.comment.parent");
        assertThatThrownBy(() -> feedbackService.createModelComment(7L, 77L, 501L,
                new CreateModelCommentRequest("답글", 31L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getMessageKey())
                .isEqualTo("detail.share.comment.parent");
        then(commentRepository).should(never()).save(any(ModelComment.class));
    }

    @Test
    @DisplayName("답글(parentCommentId)은 문서 작성자·관리자만 — 그 외 멤버는 AdminGuard에서 403")
    void createModelCommentRejectsParentForNonOwner() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L))); // createdBy=7, 요청자=8
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .given(adminGuard).requireAdmin(8L);

        assertThatThrownBy(() -> feedbackService.createModelComment(8L, 77L, 501L,
                new CreateModelCommentRequest("타인 답글", 31L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        then(commentRepository).should(never()).findByIdAndModelId(anyLong(), anyLong());
        then(commentRepository).should(never()).save(any(ModelComment.class));
    }

    @Test
    @DisplayName("멤버 댓글 수정은 본인만 — 오너·관리자도 남의 글을 수정할 수는 없다(403)")
    void updateModelCommentRejectsNonAuthor() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L)));

        for (long requester : new long[]{7L, 9L}) { // 오너(7)도, 제3자(9)도 못 고친다
            given(commentRepository.findByIdAndModelId(31L, 501L))
                    .willReturn(Optional.of(memberComment(31L, null, 8L)));
            assertThatThrownBy(() -> feedbackService.updateModelComment(requester, 77L, 501L, 31L,
                    new UpdateShareCommentRequest("타인 수정", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PERMISSION_DENIED);
        }
        given(commentRepository.findByIdAndModelId(31L, 501L))
                .willReturn(Optional.of(guestComment(31L, GUEST_PASSWORD)));
        assertThatThrownBy(() -> feedbackService.updateModelComment(7L, 77L, 501L, 31L,
                new UpdateShareCommentRequest("비회원 수정", null))) // 비회원 댓글은 신원이 없다
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        then(commentRepository).should(never()).save(any(ModelComment.class));
    }

    @Test
    @DisplayName("오너 관리 삭제는 문서의 모든 댓글을 지운다 — 비회원 댓글도, 카운터는 1+답글만큼")
    void deleteModelCommentByOwnerRemovesAnyComment() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L))); // 오너=7
        ModelComment comment = guestComment(31L, GUEST_PASSWORD); // 작성자=비회원 → 오너 판정으로 삭제
        given(commentRepository.findByIdAndModelId(31L, 501L)).willReturn(Optional.of(comment));
        given(commentRepository.countByParentCommentId(31L)).willReturn(1L);

        feedbackService.deleteModelComment(7L, 77L, 501L, 31L);

        then(commentRepository).should().delete(comment);
        then(modelRepository).should().addCommentCount(501L, -2L);
        then(auditRecorder).should().record(7L, "MODEL_COMMENT_DELETED", "MODEL_COMMENT", "31",
                Map.of("modelId", "501", "withReplies", "1"));
    }

    @Test
    @DisplayName("본인도 지운다 — 회원 댓글 작성자의 삭제, 오너 판정은 거치지 않는다")
    void deleteModelCommentByAuthor() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L)));
        ModelComment comment = memberComment(31L, null, 8L);
        given(commentRepository.findByIdAndModelId(31L, 501L)).willReturn(Optional.of(comment));
        given(commentRepository.countByParentCommentId(31L)).willReturn(0L);

        feedbackService.deleteModelComment(8L, 77L, 501L, 31L);

        then(commentRepository).should().delete(comment);
        then(modelRepository).should().addCommentCount(501L, -1L);
        then(adminGuard).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("본인도 오너도 아닌 삭제는 AdminGuard로 넘어간다 — 관리자 아니면 403")
    void deleteModelCommentDelegatesToAdminGuard() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L))); // createdBy=7, 요청자=8
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .given(adminGuard).requireAdmin(8L);
        given(commentRepository.findByIdAndModelId(31L, 501L))
                .willReturn(Optional.of(memberComment(31L, null, 9L))); // 제3자 댓글

        assertThatThrownBy(() -> feedbackService.deleteModelComment(8L, 77L, 501L, 31L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        then(commentRepository).should(never()).delete(any(ModelComment.class));
    }

    @Test
    @DisplayName("멤버 경로의 댓글이 없으면 404 MODEL_COMMENT_NOT_FOUND다")
    void deleteModelCommentRejectsUnknownComment() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L))
                .willReturn(Optional.of(model(7L)));
        given(commentRepository.findByIdAndModelId(31L, 501L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> feedbackService.deleteModelComment(7L, 77L, 501L, 31L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MODEL_COMMENT_NOT_FOUND);
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
        assertThatThrownBy(() -> feedbackService.toggleReaction("expired", 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHARE_INACTIVE);
        then(commentQueryRepository).shouldHaveNoInteractions();
        then(reactionRepository).shouldHaveNoInteractions();
    }

    // --- 내 피드백 역조회 (1.10.9 — accounts/me)

    @Test
    @DisplayName("내 댓글 — 행에 문서 메타를 얹어 내린다. 원댓글 parentCommentId=null, 수정 이력은 edited")
    void myCommentsMapsRowsWithModelMeta() {
        Instant created = Instant.parse("2026-09-27T05:00:00Z");
        given(commentQueryRepository.findByAuthorUserIdOrderByIdDesc(7L)).willReturn(List.of(
                new ModelCommentQueryRepository.MyCommentRow(
                        13L, 12L, "감사합니다!", created, created.plusSeconds(600),
                        "tok123", "주문 서비스 ERD", "postgresql"),
                new ModelCommentQueryRepository.MyCommentRow(
                        11L, null, "구조가 한눈에 들어오네요", created, created,
                        null, "사내 문서", "mysql"))); // 링크 없는 문서 — 대표 링크 null

        var responses = feedbackService.myComments(7L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).commentId()).isEqualTo("13");
        assertThat(responses.get(0).parentCommentId()).isEqualTo("12"); // 답글
        assertThat(responses.get(0).edited()).isTrue(); // updatedAt ≠ createdAt
        assertThat(responses.get(0).shareToken()).isEqualTo("tok123");
        assertThat(responses.get(0).modelName()).isEqualTo("주문 서비스 ERD");
        assertThat(responses.get(0).databaseType()).isEqualTo("postgresql");
        assertThat(responses.get(1).parentCommentId()).isNull();
        assertThat(responses.get(1).edited()).isFalse();
        assertThat(responses.get(1).shareToken()).isNull(); // 발급된 링크가 없다
    }

    @Test
    @DisplayName("내 좋아요 문서 — 좋아요 시각과 문서 메타·카운터를 그대로 내린다")
    void myReactionsMapsRowsWithCounters() {
        Instant reactedAt = Instant.parse("2026-09-27T09:00:00Z");
        given(reactionQueryRepository.findByUserIdOrderByIdDesc(7L)).willReturn(List.of(
                new ModelReactionQueryRepository.MyReactionRow(
                        reactedAt, "tok123", "주문 서비스 ERD", "결제 도메인 1차", "postgresql",
                        128L, 46L, 12L)));

        var responses = feedbackService.myReactions(7L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).reactedAt()).isEqualTo(reactedAt);
        assertThat(responses.get(0).shareToken()).isEqualTo("tok123");
        assertThat(responses.get(0).modelName()).isEqualTo("주문 서비스 ERD");
        assertThat(responses.get(0).description()).isEqualTo("결제 도메인 1차");
        assertThat(responses.get(0).viewCount()).isEqualTo(128L);
        assertThat(responses.get(0).reactionCount()).isEqualTo(46L);
        assertThat(responses.get(0).commentCount()).isEqualTo(12L);
    }

    // --- 픽스처

    /** 활성 공유 id=9 → 문서 501 — 토큰 경로의 해석 원료 */
    private static ModelShare activeShare() {
        ModelShare share = new ModelShare(501L, "tok123", PAST, FUTURE, 7L);
        ReflectionTestUtils.setField(share, "id", 9L);
        return share;
    }

    /** 문서 501(워크스페이스 77) — createdBy 인자, reaction_count=7(문서 단위 카운터) */
    private static Model model(long createdBy) {
        Model model = new Model(77L, "주문 ERD", null, "postgresql", "{}", createdBy);
        ReflectionTestUtils.setField(model, "id", 501L);
        model.setReactionCount(7L);
        return model;
    }

    private void givenActiveModel() {
        given(shareRepository.findByShareToken("tok123")).willReturn(Optional.of(activeShare()));
        given(modelRepository.findById(501L)).willReturn(Optional.of(model(7L)));
    }

    private ModelComment guestComment(long id, String rawPassword) {
        ModelComment comment = new ModelComment(501L, "방문자", "구조 좋네요",
                passwordHasher.hash(rawPassword));
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }

    private static ModelComment memberComment(long id, Long parentCommentId, long authorUserId) {
        ModelComment comment = new ModelComment(501L, parentCommentId, "회원 댓글", authorUserId);
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }
}
