package net.java21.crowfoot.api.community.service;

import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.community.domain.CommunityBoard;
import net.java21.crowfoot.api.community.domain.CommunityComment;
import net.java21.crowfoot.api.community.domain.CommunityPost;
import net.java21.crowfoot.api.community.dto.CommunityCommentResponse;
import net.java21.crowfoot.api.community.dto.CreateCommunityCommentRequest;
import net.java21.crowfoot.api.community.dto.UpdateCommunityCommentRequest;
import net.java21.crowfoot.api.community.repository.CommunityCommentQueryRepository;
import net.java21.crowfoot.api.community.repository.CommunityCommentRepository;
import net.java21.crowfoot.api.community.repository.CommunityPostRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.verify;

/** 커뮤니티 코멘트 서비스 (08-core/08-community.md Section 3) — FEEDBACK 전용 차단·권한·감사 */
@ExtendWith(MockitoExtension.class)
class CommunityCommentServiceTest {

    @Mock
    private CommunityCommentRepository communityCommentRepository;
    @Mock
    private CommunityCommentQueryRepository communityCommentQueryRepository;
    @Mock
    private CommunityPostRepository communityPostRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private CommunityCommentService communityCommentService;

    @Test
    @DisplayName("릴리스 노트 게시글에는 코멘트를 달 수 없다 — 400 COMMUNITY_COMMENT_NOT_ALLOWED")
    void createCommentOnReleaseNoteThrowsNotAllowed() {
        // given
        CommunityPost post = new CommunityPost(CommunityBoard.RELEASE_NOTE, "v1.4.0", "# v1.4.0", 2L);
        post.setId(51L);
        given(communityPostRepository.findById(51L)).willReturn(Optional.of(post));

        // when & then
        assertThatThrownBy(() -> communityCommentService.create(7L, 51L, new CreateCommunityCommentRequest("댓글")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_ALLOWED));
        then(communityCommentRepository).should(never()).save(any(CommunityComment.class));
    }

    @Test
    @DisplayName("FEEDBACK 게시글에 코멘트를 달면 저장되고 감사가 남는다")
    void createCommentOnFeedbackSavesAndAudits() {
        // given
        CommunityPost post = new CommunityPost(CommunityBoard.FEEDBACK, "제안", "본문", 2L);
        post.setId(41L);
        given(communityPostRepository.findById(41L)).willReturn(Optional.of(post));
        given(communityCommentRepository.save(any(CommunityComment.class))).willAnswer(invocation -> {
            CommunityComment comment = invocation.getArgument(0);
            comment.setId(61L);
            return comment;
        });

        // when
        CommunityCommentResponse response = communityCommentService.create(7L, 41L, new CreateCommunityCommentRequest("좋은 제안입니다"));

        // then
        assertThat(response.commentId()).isEqualTo("61");
        assertThat(response.postId()).isEqualTo("41");
        assertThat(response.content()).isEqualTo("좋은 제안입니다");
        then(auditRecorder).should().record(7L, "COMMUNITY_COMMENT_CREATED", "COMMUNITY_COMMENT", "61",
                Map.of("postId", 41L));
    }

    @Test
    @DisplayName("릴리스 노트 게시글의 코멘트 목록도 400으로 차단한다 — 읽기 전용 계약")
    void listCommentsOnReleaseNoteThrowsNotAllowed() {
        // given
        CommunityPost post = new CommunityPost(CommunityBoard.RELEASE_NOTE, "v1.4.0", "# v1.4.0", 2L);
        post.setId(51L);
        given(communityPostRepository.findById(51L)).willReturn(Optional.of(post));

        // when & then
        assertThatThrownBy(() -> communityCommentService.list(51L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_ALLOWED));
    }

    @Test
    @DisplayName("코멘트 수정은 작성자 본인이면 갱신된다")
    void patchCommentByAuthorUpdates() {
        // given
        CommunityComment comment = new CommunityComment(41L, "원래 댓글", 7L);
        comment.setId(61L);
        given(communityCommentRepository.findById(61L)).willReturn(Optional.of(comment));

        // when
        CommunityCommentResponse response = communityCommentService.patch(7L, 61L,
                new UpdateCommunityCommentRequest("바뀐 댓글"));

        // then
        assertThat(response.content()).isEqualTo("바뀐 댓글");
        verify(adminGuard, never()).requireAdmin(anyLong());
    }

    @Test
    @DisplayName("코멘트 수정은 타인 작성이면 관리자 판정으로 넘어간다 — 비관리자면 403")
    void patchCommentByOtherUserThrowsPermissionDenied() {
        // given
        CommunityComment comment = new CommunityComment(41L, "댓글", 7L);
        comment.setId(61L);
        given(communityCommentRepository.findById(61L)).willReturn(Optional.of(comment));
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED)).given(adminGuard).requireAdmin(8L);

        // when & then
        assertThatThrownBy(() -> communityCommentService.patch(8L, 61L, new UpdateCommunityCommentRequest("바뀐 댓글")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("코멘트 삭제는 관리자면 작성자가 아니어도 삭제된다")
    void deleteCommentByAdminDeletes() {
        // given
        CommunityComment comment = new CommunityComment(41L, "댓글", 7L);
        comment.setId(61L);
        given(communityCommentRepository.findById(61L)).willReturn(Optional.of(comment));

        // when
        communityCommentService.delete(8L, 61L);

        // then
        then(communityCommentRepository).should().delete(comment);
    }

    @Test
    @DisplayName("없는 코멘트 접근은 404 COMMUNITY_COMMENT_NOT_FOUND")
    void commentNotFoundThrows() {
        // given
        given(communityCommentRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> communityCommentService.delete(7L, 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("없는 게시글에 코멘트를 달면 404 COMMUNITY_POST_NOT_FOUND")
    void createCommentOnMissingPostThrows() {
        // given
        given(communityPostRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> communityCommentService.create(7L, 99L, new CreateCommunityCommentRequest("댓글")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
    }
}
