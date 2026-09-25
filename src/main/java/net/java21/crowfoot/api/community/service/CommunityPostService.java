package net.java21.crowfoot.api.community.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.community.domain.CommunityBoard;
import net.java21.crowfoot.api.community.domain.CommunityPost;
import net.java21.crowfoot.api.community.dto.CommunityPageParams;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 커뮤니티 게시글 API (08-core/08-community.md Section 3) — 목록·최근글·상세·생성·수정·삭제.
 *
 * <p>권한 규칙 — RELEASE_NOTE(릴리스 노트) 작성은 관리자 전용(AdminGuard),
 * 수정·삭제는 작성자 본인 또는 관리자(위반 시 403 PERMISSION_DENIED — 존재 은닉).
 * 워크스페이스 역할(RoleChecker)과 무관한 전역 기능이다.
 *
 * <p>다국어 본문(§2.1) — 읽기는 ?lang=로 해석(폴백: 요청 언어→en→ko→첫값)하고 availableLangs를 함께
 * 내려준다. 쓰기는 문자열({ko:값} 병합 — 구버전 호환) 또는 언어 객체(값 있는 키만 병합)를 받는다.
 */
@Service
@RequiredArgsConstructor
public class CommunityPostService {

    /** 최근글 상한 — 대시보드 위젯 용도를 넘지 않도록 */
    private static final int MAX_RECENT_LIMIT = 20;
    private static final int DEFAULT_RECENT_LIMIT = 5;

    /** 제목·본문 언어별 값 상한 — DTO 검증이 커스텀 타입이라 서비스가 검증한다 */
    private static final int TITLE_MAX = 200;
    private static final int CONTENT_MAX = 1_000_000;

    private final CommunityPostRepository communityPostRepository;
    private final CommunityPostQueryRepository communityPostQueryRepository;
    private final CommunityCommentQueryRepository communityCommentQueryRepository;
    private final UserRepository userRepository;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;

    /** 목록 — 게시판 내 최신순(id desc), keyword는 제목 부분 일치, title은 lang 해석 */
    @Transactional(readOnly = true)
    public ListApiResponse<CommunityPostSummaryResponse> list(String boardValue, String keyword,
                                                              Integer page, Integer size, String lang) {
        CommunityBoard board = CommunityBoard.fromValue(boardValue);
        String trimmed = keyword == null ? null : keyword.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        CommunityPageParams params = CommunityPageParams.of(page, size);

        long totalCount = communityPostQueryRepository.count(board, trimmed);
        if (totalCount == 0) {
            return ListApiResponse.paged(List.of(), params.page(), params.size(), 0);
        }
        List<PostRow> rows = communityPostQueryRepository.search(board, trimmed, params.offset(), params.size());
        Map<Long, Long> commentCounts = communityCommentQueryRepository.countByPostIds(idsOf(rows));
        List<CommunityPostSummaryResponse> responses = rows.stream()
                .map(row -> toSummary(row, commentCounts.getOrDefault(row.id(), 0L), lang))
                .toList();
        return ListApiResponse.paged(responses, params.page(), params.size(), totalCount);
    }

    /** 최근글 — 게시판 무관 최신순(대시보드 통합 위젯) */
    @Transactional(readOnly = true)
    public ListApiResponse<CommunityRecentPostResponse> recent(Integer limit, String lang) {
        List<PostRow> rows = communityPostQueryRepository.recent(resolveRecentLimit(limit));
        return toRecentResponses(rows, lang);
    }

    /** 공개 최근 릴리스 노트 — RELEASE_NOTE만 최신순(랜딩 위젯, 무인증) */
    @Transactional(readOnly = true)
    public ListApiResponse<CommunityRecentPostResponse> recentReleaseNotes(Integer limit, String lang) {
        List<PostRow> rows = communityPostQueryRepository.recentByBoard(CommunityBoard.RELEASE_NOTE,
                resolveRecentLimit(limit));
        return toRecentResponses(rows, lang);
    }

    /** 상세 — 마크다운 원문 포함(lang 해석) */
    @Transactional(readOnly = true)
    public CommunityPostDetailResponse detail(long postId, String lang) {
        CommunityPost post = requirePost(postId);
        return toDetail(post, lang);
    }

    /** 공개 릴리스 노트 상세 — RELEASE_NOTE가 아니면 404(존재 은닉, 무인증) */
    @Transactional(readOnly = true)
    public CommunityPostDetailResponse releaseNoteDetail(long postId, String lang) {
        CommunityPost post = requirePost(postId);
        if (post.getBoard() != CommunityBoard.RELEASE_NOTE) {
            throw new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND);
        }
        return toDetail(post, lang);
    }

    /** 생성 — RELEASE_NOTE는 관리자만, FEEDBACK은 로그인 사용자 전체 */
    @Transactional
    public CommunityPostDetailResponse create(long userId, CreateCommunityPostRequest request) {
        CommunityBoard board = CommunityBoard.fromValue(request.board());
        if (board == CommunityBoard.RELEASE_NOTE) {
            adminGuard.requireAdmin(userId);
        }
        Map<String, String> title = validated(request.title(), TITLE_MAX, true);
        Map<String, String> content = validated(request.content(), CONTENT_MAX, false);
        title.replaceAll((langKey, value) -> value.trim());
        CommunityPost post = communityPostRepository.save(new CommunityPost(board, title, content, userId));
        auditRecorder.record(userId, "COMMUNITY_POST_CREATED", "COMMUNITY_POST", post.getId().toString(),
                Map.of("board", board.name()));
        return toDetail(post, "ko");
    }

    /** 수정 — 작성자 본인 또는 관리자, board는 변경 불가(DTO에 없음). 다국어 값 있는 키만 병합(부분 갱신) */
    @Transactional
    public CommunityPostDetailResponse patch(long userId, long postId, UpdateCommunityPostRequest request) {
        CommunityPost post = requirePost(postId);
        requireAuthorOrAdmin(userId, post.getCreatedBy());
        LinkedHashMap<String, String> mergedTitle = LocalizedTexts.fromJson(post.getTitleI18n());
        if (request.title() != null) {
            mergedTitle.putAll(validated(request.title(), TITLE_MAX, false));
        }
        LinkedHashMap<String, String> mergedContent = LocalizedTexts.fromJson(post.getContentI18n());
        if (request.content() != null) {
            mergedContent.putAll(validated(request.content(), CONTENT_MAX, false));
        }
        if (mergedTitle.isEmpty() || mergedContent.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        mergedTitle.replaceAll((langKey, value) -> value.trim());
        post.setTitleI18n(LocalizedTexts.toJson(mergedTitle));
        post.setContentI18n(LocalizedTexts.toJson(mergedContent));
        auditRecorder.record(userId, "COMMUNITY_POST_UPDATED", "COMMUNITY_POST", post.getId().toString(),
                Map.of("board", post.getBoard().name()));
        return toDetail(post, "ko");
    }

    /** 삭제 — 작성자 본인 또는 관리자, 코멘트는 FK CASCADE로 함께 소멸 */
    @Transactional
    public void delete(long userId, long postId) {
        CommunityPost post = requirePost(postId);
        requireAuthorOrAdmin(userId, post.getCreatedBy());
        communityPostRepository.delete(post);
        auditRecorder.record(userId, "COMMUNITY_POST_DELETED", "COMMUNITY_POST", post.getId().toString(),
                Map.of("board", post.getBoard().name()));
    }

    private CommunityPost requirePost(long postId) {
        return communityPostRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
    }

    /** 작성자 본인이면 통과, 아니면 관리자 여부로 판정(AdminGuard가 403을 던진다) */
    private void requireAuthorOrAdmin(long userId, Long createdBy) {
        if (createdBy == null || createdBy != userId) {
            adminGuard.requireAdmin(userId);
        }
    }

    private static List<Long> idsOf(List<PostRow> rows) {
        return rows.stream().map(PostRow::id).toList();
    }

    /** 최근글 limit 정규화 — null·1 미만이면 기본값, 상한 20 */
    private static int resolveRecentLimit(Integer limit) {
        return (limit == null || limit < 1) ? DEFAULT_RECENT_LIMIT : Math.min(limit, MAX_RECENT_LIMIT);
    }

    /** 쓰기 값 검증 — null은 통과(PATCH 부분 갱신), 최소 1개 언어·값별 길이 상한. trim은 제목에만 적용한다 */
    private static Map<String, String> validated(LocalizedText text, int max, boolean required) {
        if (text == null) {
            if (required) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            return Map.of();
        }
        if (text.isEmpty()) {
            if (required) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            return Map.of();
        }
        for (Map.Entry<String, String> entry : text.values().entrySet()) {
            if (entry.getValue().length() > max) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
        return text.values();
    }

    private ListApiResponse<CommunityRecentPostResponse> toRecentResponses(List<PostRow> rows, String lang) {
        Map<Long, Long> commentCounts = communityCommentQueryRepository.countByPostIds(idsOf(rows));
        List<CommunityRecentPostResponse> responses = rows.stream()
                .map(row -> new CommunityRecentPostResponse(row.id().toString(), row.board().name(),
                        LocalizedTexts.resolve(row.titleI18n(), lang),
                        LocalizedTexts.availableLangs(row.titleI18n()),
                        toAuthor(row), commentCounts.getOrDefault(row.id(), 0L), row.createdAt()))
                .toList();
        return ListApiResponse.of(responses);
    }

    private CommunityPostSummaryResponse toSummary(PostRow row, long commentCount, String lang) {
        return new CommunityPostSummaryResponse(row.id().toString(), row.board().name(),
                LocalizedTexts.resolve(row.titleI18n(), lang), LocalizedTexts.availableLangs(row.titleI18n()),
                toAuthor(row), commentCount, row.createdAt(), row.updatedAt());
    }

    /** 상세 응답 조립 — 생성·수정 직후 엔티티에는 작성자 이름이 없어 별도 조회(FK로 항상 존재) */
    private CommunityPostDetailResponse toDetail(CommunityPost post, String lang) {
        String authorName = userRepository.findById(post.getCreatedBy())
                .map(user -> user.getName())
                .orElse(null);
        return new CommunityPostDetailResponse(post.getId().toString(), post.getBoard().name(),
                LocalizedTexts.resolve(post.getTitleI18n(), lang), LocalizedTexts.availableLangs(post.getTitleI18n()),
                LocalizedTexts.resolve(post.getContentI18n(), lang),
                new UserRefResponse(post.getCreatedBy().toString(), authorName),
                post.getCreatedAt(), post.getUpdatedAt());
    }

    private static UserRefResponse toAuthor(PostRow row) {
        return new UserRefResponse(row.createdBy() == null ? null : row.createdBy().toString(), row.authorName());
    }
}
