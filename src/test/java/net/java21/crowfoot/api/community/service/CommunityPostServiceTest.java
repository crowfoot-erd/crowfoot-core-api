package net.java21.crowfoot.api.community.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.community.domain.CommunityBoard;
import net.java21.crowfoot.api.community.domain.CommunityPost;
import net.java21.crowfoot.api.community.dto.CommunityPostDetailResponse;
import net.java21.crowfoot.api.community.dto.CommunityPostSummaryResponse;
import net.java21.crowfoot.api.community.dto.CommunityRecentPostResponse;
import net.java21.crowfoot.api.community.dto.CreateCommunityPostRequest;
import net.java21.crowfoot.api.community.dto.UpdateCommunityPostRequest;
import net.java21.crowfoot.api.community.repository.CommunityCommentQueryRepository;
import net.java21.crowfoot.api.community.repository.CommunityPostQueryRepository;
import net.java21.crowfoot.api.community.repository.CommunityPostQueryRepository.PostRow;
import net.java21.crowfoot.api.community.repository.CommunityPostRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import net.java21.crowfoot.common.i18n.LocalizedText;
import net.java21.crowfoot.common.i18n.LocalizedTexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/** 커뮤니티 게시글 서비스 (08-core/08-community.md Section 3) — 권한(릴리스 노트 관리자 전용·작성자/관리자)·목록·최근글·감사·다국어 본문(§2.1) */
@ExtendWith(MockitoExtension.class)
class CommunityPostServiceTest {

    @Mock
    private CommunityPostRepository communityPostRepository;
    @Mock
    private CommunityPostQueryRepository communityPostQueryRepository;
    @Mock
    private CommunityCommentQueryRepository communityCommentQueryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private CommunityPostService communityPostService;

    @Test
    @DisplayName("릴리스 노트 생성은 관리자가 아니면 403 — AdminGuard가 판정한다")
    void createReleaseNoteWithoutAdminThrowsPermissionDenied() {
        // given
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED)).given(adminGuard).requireAdmin(7L);

        // when & then
        assertThatThrownBy(() -> communityPostService.create(7L, new CreateCommunityPostRequest(
                "RELEASE_NOTE", LocalizedText.of("v1.4.0"), LocalizedText.of("# v1.4.0"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        then(communityPostRepository).should(never()).save(any(CommunityPost.class));
    }

    @Test
    @DisplayName("FEEDBACK 생성은 로그인 사용자면 저장되고 감사가 남는다 — 문자열 제목·본문은 {ko:값}으로 정규화")
    void createFeedbackPostSavesAndAudits() {
        // given
        given(communityPostRepository.save(any(CommunityPost.class))).willAnswer(invocation -> {
            CommunityPost post = invocation.getArgument(0);
            post.setId(41L);
            return post;
        });
        given(userRepository.findById(7L)).willReturn(Optional.of(new User("marco@x.com", "marco", false)));

        // when
        CommunityPostDetailResponse response = communityPostService.create(7L, new CreateCommunityPostRequest(
                "FEEDBACK", LocalizedText.of("검색 필터 개선 제안"), LocalizedText.of("본문")));

        // then
        assertThat(response.postId()).isEqualTo("41");
        assertThat(response.board()).isEqualTo("FEEDBACK");
        assertThat(response.title()).isEqualTo("검색 필터 개선 제안");
        assertThat(response.availableLangs()).containsExactly("ko");
        assertThat(response.author().name()).isEqualTo("marco");
        verify(adminGuard, never()).requireAdmin(anyLong());
        then(auditRecorder).should().record(7L, "COMMUNITY_POST_CREATED", "COMMUNITY_POST", "41", Map.of("board", "FEEDBACK"));
    }

    @Test
    @DisplayName("릴리스 노트 생성은 4개 언어 객체로 저장되고 ?lang=으로 해석된다")
    void createReleaseNoteWithFourLanguages() {
        // given
        CommunityPost saved = new CommunityPost(CommunityBoard.RELEASE_NOTE,
                Map.of("ko", "v1.16 — 4개 언어", "en", "v1.16 — Four languages",
                        "ja", "v1.16 — 4言語", "zh", "v1.16 — 四种语言"),
                Map.of("ko", "## 주요 기능", "en", "## Highlights",
                        "ja", "## 主な機能", "zh", "## 主要功能"), 2L);
        saved.setId(42L);
        given(communityPostRepository.save(any(CommunityPost.class))).willReturn(saved);
        given(communityPostRepository.findById(42L)).willReturn(Optional.of(saved));
        given(userRepository.findById(2L)).willReturn(Optional.of(new User("admin@x.com", "관리자", true)));

        // when
        CommunityPostDetailResponse ko = communityPostService.create(2L, new CreateCommunityPostRequest(
                "RELEASE_NOTE",
                LocalizedText.of(Map.of("ko", "v1.16 — 4개 언어", "en", "v1.16 — Four languages",
                        "ja", "v1.16 — 4言語", "zh", "v1.16 — 四种语言")),
                LocalizedText.of(Map.of("ko", "## 주요 기능", "en", "## Highlights",
                        "ja", "## 主な機能", "zh", "## 主要功能"))));
        CommunityPostDetailResponse ja = communityPostService.detail(42L, "ja");

        // then
        assertThat(ko.title()).isEqualTo("v1.16 — 4개 언어"); // 생성 응답은 ko 해석(§3.4)
        assertThat(ja.title()).isEqualTo("v1.16 — 4言語");
        assertThat(ja.content()).isEqualTo("## 主な機能");
        assertThat(ja.availableLangs()).containsExactly("ko", "en", "ja", "zh");
    }

    @Test
    @DisplayName("읽기 폴백 체인 — 요청 언어 → en → ko → 첫값(§2.1)")
    void resolveFallsBackToEnThenKo() {
        // given: en만 있는 글
        CommunityPost enOnly = new CommunityPost(CommunityBoard.RELEASE_NOTE,
                Map.of("en", "v1.16 — Four languages"), Map.of("en", "## Highlights"), 2L);
        enOnly.setId(43L);
        given(communityPostRepository.findById(43L)).willReturn(Optional.of(enOnly));
        // ko만 있는 글(백필 형태)
        CommunityPost koOnly = new CommunityPost(CommunityBoard.RELEASE_NOTE,
                Map.of("ko", "v1.15"), Map.of("ko", "## 주요 기능"), 2L);
        koOnly.setId(44L);
        given(communityPostRepository.findById(44L)).willReturn(Optional.of(koOnly));

        // when & then
        assertThat(communityPostService.detail(43L, "zh").title()).isEqualTo("v1.16 — Four languages"); // zh → en
        assertThat(communityPostService.detail(44L, "ja").title()).isEqualTo("v1.15"); // ja → en 없음 → ko
    }

    @Test
    @DisplayName("무효 board 값은 목록·생성 모두 400 INVALID_REQUEST")
    void rejectsInvalidBoardValue() {
        // given
        String invalidBoard = "NOTICE";

        // when & then
        assertThatThrownBy(() -> communityPostService.list(invalidBoard, null, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> communityPostService.create(7L, new CreateCommunityPostRequest(
                invalidBoard, LocalizedText.of("제목"), LocalizedText.of("본문"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("빈 다국어 텍스트(값 없음)로는 생성할 수 없다 — 400 INVALID_REQUEST")
    void createRejectsEmptyLocalizedText() {
        // when & then
        assertThatThrownBy(() -> communityPostService.create(7L, new CreateCommunityPostRequest(
                "FEEDBACK", LocalizedText.of(""), LocalizedText.of("본문"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("수정은 작성자 본인이면 관리자 판정 없이 갱신된다")
    void patchByAuthorUpdatesFields() {
        // given
        CommunityPost post = new CommunityPost(CommunityBoard.FEEDBACK, Map.of("ko", "원래 제목"),
                Map.of("ko", "원래 본문"), 7L);
        post.setId(41L);
        given(communityPostRepository.findById(41L)).willReturn(Optional.of(post));
        given(userRepository.findById(7L)).willReturn(Optional.of(new User("marco@x.com", "marco", false)));

        // when
        CommunityPostDetailResponse response = communityPostService.patch(7L, 41L,
                new UpdateCommunityPostRequest(LocalizedText.of("바뀐 제목"), LocalizedText.of("바뀐 본문")));

        // then
        assertThat(response.title()).isEqualTo("바뀐 제목");
        assertThat(response.content()).isEqualTo("바뀐 본문");
        verify(adminGuard, never()).requireAdmin(anyLong());
    }

    @Test
    @DisplayName("수정의 언어 객체는 값 있는 키만 병합한다 — 부분 갱신, 다른 언어는 유지(§3.5)")
    void patchMergesOnlyProvidedLanguages() {
        // given
        CommunityPost post = new CommunityPost(CommunityBoard.RELEASE_NOTE,
                Map.of("ko", "v1.16 — 4개 언어", "en", "v1.16 — Four languages"),
                Map.of("ko", "## 주요 기능", "en", "## Highlights"), 2L);
        post.setId(45L);
        given(communityPostRepository.findById(45L)).willReturn(Optional.of(post));
        given(userRepository.findById(2L)).willReturn(Optional.of(new User("admin@x.com", "관리자", true)));

        // when: en 제목만 갱신
        CommunityPostDetailResponse response = communityPostService.patch(2L, 45L,
                new UpdateCommunityPostRequest(LocalizedText.of(Map.of("en", "v1.16 — Four languages!")), null));

        // then
        assertThat(response.title()).isEqualTo("v1.16 — 4개 언어"); // ko 응답 해석은 그대로
        assertThat(post.titleMap()).containsEntry("en", "v1.16 — Four languages!");
        assertThat(post.titleMap()).containsEntry("ko", "v1.16 — 4개 언어"); // 병합 — 기존값 유지
        assertThat(post.contentMap()).containsEntry("ko", "## 주요 기능"); // content 미지정 → 유지
    }

    @Test
    @DisplayName("수정은 작성자가 아닌 비관리자면 403 — AdminGuard 위임")
    void patchByNonAuthorNonAdminThrowsPermissionDenied() {
        // given
        CommunityPost post = new CommunityPost(CommunityBoard.FEEDBACK, Map.of("ko", "제목"),
                Map.of("ko", "본문"), 7L);
        post.setId(41L);
        given(communityPostRepository.findById(41L)).willReturn(Optional.of(post));
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED)).given(adminGuard).requireAdmin(8L);

        // when & then
        assertThatThrownBy(() -> communityPostService.patch(8L, 41L,
                new UpdateCommunityPostRequest(LocalizedText.of("제목"), LocalizedText.of("본문"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("삭제는 관리자면 작성자가 아니어도 삭제되고 감사가 남는다")
    void deleteByAdminDeletesAndAudits() {
        // given
        CommunityPost post = new CommunityPost(CommunityBoard.FEEDBACK, Map.of("ko", "제목"),
                Map.of("ko", "본문"), 7L);
        post.setId(41L);
        given(communityPostRepository.findById(41L)).willReturn(Optional.of(post));

        // when
        communityPostService.delete(8L, 41L);

        // then
        then(communityPostRepository).should().delete(post);
        then(auditRecorder).should().record(eq(8L), eq("COMMUNITY_POST_DELETED"), eq("COMMUNITY_POST"), eq("41"), anyMap());
    }

    @Test
    @DisplayName("목록은 count·search·코멘트 건수 합성으로 페이징 응답을 만든다 — ?lang= 해석 포함")
    void listAppliesPagingAndMapsCommentCount() {
        // given
        given(communityPostQueryRepository.count(CommunityBoard.FEEDBACK, null)).willReturn(1L);
        given(communityPostQueryRepository.search(CommunityBoard.FEEDBACK, null, 0, 20))
                .willReturn(List.of(new PostRow(41L, CommunityBoard.FEEDBACK,
                        LocalizedTexts.toJson(Map.of("ko", "제안", "en", "Suggestion")), 7L, "marco",
                        Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"))));
        given(communityCommentQueryRepository.countByPostIds(List.of(41L))).willReturn(Map.of(41L, 3L));

        // when
        ListApiResponse<CommunityPostSummaryResponse> en = communityPostService.list("FEEDBACK", null, 1, 20, "en");

        // then
        assertThat(en.totalCount()).isEqualTo(1);
        assertThat(en.page()).isEqualTo(1);
        assertThat(en.responses()).hasSize(1);
        assertThat(en.responses().get(0).commentCount()).isEqualTo(3);
        assertThat(en.responses().get(0).author().name()).isEqualTo("marco");
        assertThat(en.responses().get(0).title()).isEqualTo("Suggestion");
        assertThat(en.responses().get(0).availableLangs()).containsExactly("ko", "en");
    }

    @Test
    @DisplayName("count가 0이면 search하지 않고 빈 페이지를 응답한다")
    void listEmptyShortCircuits() {
        // given
        given(communityPostQueryRepository.count(CommunityBoard.RELEASE_NOTE, null)).willReturn(0L);

        // when
        ListApiResponse<CommunityPostSummaryResponse> result = communityPostService.list("RELEASE_NOTE", null, null, null, null);

        // then
        assertThat(result.responses()).isEmpty();
        assertThat(result.totalCount()).isZero();
        then(communityPostQueryRepository).should(never()).search(any(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("최근글 limit은 null이면 5, 20을 넘으면 20으로 조정한다")
    void recentClampsLimit() {
        // given
        given(communityPostQueryRepository.recent(5)).willReturn(List.of());
        given(communityPostQueryRepository.recent(20)).willReturn(List.of());
        given(communityCommentQueryRepository.countByPostIds(List.of())).willReturn(Map.of());

        // when
        communityPostService.recent(null, null);
        communityPostService.recent(99, null);

        // then
        then(communityPostQueryRepository).should().recent(5);
        then(communityPostQueryRepository).should().recent(20);
    }

    @Test
    @DisplayName("공개 최근 릴리스 노트는 RELEASE_NOTE 보드로 조회하고 limit을 조정한다")
    void recentReleaseNotesQueriesReleaseNoteBoardWithClampedLimit() {
        // given
        given(communityPostQueryRepository.recentByBoard(CommunityBoard.RELEASE_NOTE, 3)).willReturn(List.of(
                new PostRow(9L, CommunityBoard.RELEASE_NOTE, LocalizedTexts.toJson(Map.of("ko", "v1.08")), 1L, "관리자",
                        Instant.parse("2026-09-18T00:00:00Z"), Instant.parse("2026-09-18T00:00:00Z"))));
        given(communityPostQueryRepository.recentByBoard(CommunityBoard.RELEASE_NOTE, 20)).willReturn(List.of());
        given(communityCommentQueryRepository.countByPostIds(anyList())).willReturn(Map.of());

        // when
        ListApiResponse<CommunityRecentPostResponse> clamped = communityPostService.recentReleaseNotes(99, null);
        ListApiResponse<CommunityRecentPostResponse> three = communityPostService.recentReleaseNotes(3, null);

        // then
        then(communityPostQueryRepository).should().recentByBoard(CommunityBoard.RELEASE_NOTE, 20); // 99 → 20
        assertThat(three.responses()).hasSize(1);
        assertThat(three.responses().get(0).board()).isEqualTo("RELEASE_NOTE");
        assertThat(three.responses().get(0).title()).isEqualTo("v1.08");
        assertThat(clamped.responses()).isEmpty();
    }

    @Test
    @DisplayName("공개 상세는 RELEASE_NOTE 글이면 마크다운 원문을 내려준다")
    void releaseNoteDetailReturnsReleaseNote() {
        // given
        CommunityPost post = new CommunityPost(CommunityBoard.RELEASE_NOTE, Map.of("ko", "v1.08"),
                Map.of("ko", "## 주요 기능"), 1L);
        post.setId(9L);
        given(communityPostRepository.findById(9L)).willReturn(Optional.of(post));
        given(userRepository.findById(1L)).willReturn(Optional.of(new User("admin@x.com", "관리자", true)));

        // when
        CommunityPostDetailResponse response = communityPostService.releaseNoteDetail(9L, null);

        // then
        assertThat(response.postId()).isEqualTo("9");
        assertThat(response.board()).isEqualTo("RELEASE_NOTE");
        assertThat(response.content()).isEqualTo("## 주요 기능");
    }

    @Test
    @DisplayName("공개 상세는 다른 게시판(FEEDBACK)의 post-id면 404 — 존재 은닉")
    void releaseNoteDetailHidesFeedbackPost() {
        // given
        CommunityPost post = new CommunityPost(CommunityBoard.FEEDBACK, Map.of("ko", "제안"),
                Map.of("ko", "본문"), 7L);
        post.setId(802L);
        given(communityPostRepository.findById(802L)).willReturn(Optional.of(post));

        // when & then
        assertThatThrownBy(() -> communityPostService.releaseNoteDetail(802L, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
    }

    @Test
    @DisplayName("상세 대상이 없으면 404 COMMUNITY_POST_NOT_FOUND")
    void detailThrowsPostNotFound() {
        // given
        given(communityPostRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> communityPostService.detail(99L, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
    }
}
