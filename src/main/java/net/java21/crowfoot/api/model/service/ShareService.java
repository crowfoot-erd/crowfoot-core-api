package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.dto.CreateShareRequest;
import net.java21.crowfoot.api.model.dto.GalleryShareResponse;
import net.java21.crowfoot.api.model.dto.ModelShareResponse;
import net.java21.crowfoot.api.model.dto.PublicShareResponse;
import net.java21.crowfoot.api.model.dto.SitemapShareResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.model.repository.ShareQueryRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 문서 공유 링크 API (08-core/02-model.md Section 1.10) — 발급·목록·철회(관리, Editor 이상)와
 * 토큰 기반 공개 조회(무인증). 링크는 문서의 읽기 전용 스냅샷이 아니라 최신 본문을 노출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareService {

    /** 갤러리 인기 구간 — 반응 수 우선 상위 N건을 최근 공유보다 먼저 띄운다(1.10.5) */
    private static final int POPULAR_LIMIT = 3;
    /** 갤러리 최근 구간 상한 — 인기 3을 제외한 나머지 최근 공유 건수(랜딩 "최근 공유" 카드 수) */
    private static final int RECENT_LIMIT = 15;
    /** 갤러리 총량 상한 — 인기 + 최근 공유 합산 */
    private static final int GALLERY_LIMIT = POPULAR_LIMIT + RECENT_LIMIT;
    /** 사이트맵 상한(1.10.10) — 이보다 많으면 최신 순으로 자르고 경고 로그 */
    private static final int SITEMAP_LIMIT = 5_000;

    private final ModelShareRepository shareRepository;
    private final ModelRepository modelRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ShareTokenGenerator tokenGenerator;
    private final ShareQueryRepository shareQueryRepository;

    /** 발급(Editor 이상) — startsAt > endsAt이면 400, 없는 문서면 404 */
    @Transactional
    public ModelShareResponse create(long userId, long workspaceId, long modelId, CreateShareRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        Instant startsAt = request == null ? null : request.startsAt();
        Instant endsAt = request == null ? null : request.endsAt();
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.share.period");
        }
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));

        String token = tokenGenerator.generateUnique(shareRepository::existsByShareToken);
        ModelShare share = shareRepository.save(
                new ModelShare(model.getId(), token, startsAt, endsAt, userId));
        auditRecorder.record(userId, "MODEL_SHARED", "MODEL",
                Long.toString(modelId), Map.of(
                        "shareId", Long.toString(share.getId()),
                        "startsAt", startsAt == null ? "" : startsAt.toString(),
                        "endsAt", endsAt == null ? "" : endsAt.toString()));
        return toResponse(share, model);
    }

    /** 목록(Editor 이상) — 없는 문서면 404, 최근 발급순. 카운터는 조회 수만 링크 고유, 반응·댓글 수는 문서 단위다 */
    @Transactional(readOnly = true)
    public List<ModelShareResponse> list(long userId, long workspaceId, long modelId) {
        roleChecker.requireEditor(userId, workspaceId);
        Model model = modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
        return shareRepository.findByModelIdOrderByCreatedAtDescIdDesc(modelId)
                .stream()
                .map(share -> toResponse(share, model))
                .toList();
    }

    /** 철회(Editor 이상) — 링크가 즉시 무효화된다, 없는 문서·링크는 404 */
    @Transactional
    public void revoke(long userId, long workspaceId, long modelId, long shareId) {
        roleChecker.requireEditor(userId, workspaceId);
        if (modelRepository.findByIdAndWorkspaceId(modelId, workspaceId).isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND);
        }
        if (shareRepository.findByIdAndModelId(shareId, modelId).isEmpty()) {
            throw new BusinessException(ErrorCode.SHARE_NOT_FOUND);
        }
        shareRepository.deleteByIdAndModelId(shareId, modelId);
        auditRecorder.record(userId, "MODEL_SHARE_REVOKED", "MODEL",
                Long.toString(modelId), Map.of("shareId", Long.toString(shareId)));
    }

    /**
     * 공개 조회(무인증) — 토큰을 아는 누구나. 시작 전·종료 후면 410 SHARE_INACTIVE로
     * 링크의 죽음을 알린다. 문서가 삭제되었으면(FK CASCADE로 링크도 삭제) 404 SHARE_NOT_FOUND.
     * 조회 수는 countView인 성공 응답마다 원자 증가한다(단순 카운트, 갤러리 인기 원료) — 컨트롤러가
     * crowfoot_share_views 쿠키로 판정한 대로 30분 창 안의 같은 방문자 재조회는 세지 않는다.
     */
    @Transactional
    public PublicShareResponse resolve(String token, boolean countView) {
        ModelShare share = shareRepository.findByShareToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        if (!isActive(share, Instant.now())) {
            throw new BusinessException(ErrorCode.SHARE_INACTIVE);
        }
        Model model = modelRepository.findById(share.getModelId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_NOT_FOUND));
        if (countView) {
            shareRepository.incrementViewCount(token);
        }
        return new PublicShareResponse(
                model.getName(),
                model.getDescription(),
                model.getDatabaseType(),
                (int) model.getVersion(),
                model.getContent(),
                share.getStartsAt(),
                share.getEndsAt());
    }

    /**
     * 공개 갤러리(무인증, 08-core/02-model.md Section 1.10.5) — 현재 공유 중인 문서의 목록.
     * 활성 링크(기간 내)만, 문서당 최근 발급 링크 1개. **반응 수 우선 상위 {@value POPULAR_LIMIT}건(인기)을
     * 먼저, 나머지를 최근 공유순으로 인기 {@value POPULAR_LIMIT}+최근 {@value RECENT_LIMIT}건**까지
     * 내려준다 — 랜딩의 인기 박스 3+최근 카드 구성. 인기 산정은 능동 신호(반응)를 먼저 본다:
     * reactionCount desc → viewCount desc → 최근 공유순(v1.21부터).
     * 전 워크스페이스의 공유를 모은다(템플릿 문서 포함 — 랜딩의 템플릿 전용 섹션은 폐지됐다).
     * 본문(content, 최대 5MB)은 미포함 — 랜딩 카드는 메타만 보여준다.
     */
    @Transactional(readOnly = true)
    public List<GalleryShareResponse> gallery() {
        Instant now = Instant.now();
        Map<Long, ModelShare> latestByModel = new LinkedHashMap<>();
        for (ModelShare share : shareRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            if (isActive(share, now)) {
                latestByModel.putIfAbsent(share.getModelId(), share); // 최근 발급순이라 선두가 그 문서의 최신 링크
            }
        }
        if (latestByModel.isEmpty()) {
            return List.of();
        }
        Map<Long, Model> models = modelRepository.findAllById(latestByModel.keySet()).stream()
                .collect(Collectors.toMap(Model::getId, Function.identity()));
        record Item(ModelShare share, Model model) {}
        List<Item> items = latestByModel.entrySet().stream()
                .filter(entry -> models.containsKey(entry.getKey())) // 방어 — CASCADE 삭제로 사실상 없는 경우
                .map(entry -> new Item(entry.getValue(), models.get(entry.getKey())))
                .toList();
        // 인기 구간 — 반응 수 desc → 조회수 desc(동률은 최근 공유순), 나머지는 최근 공유순으로 상한까지 채운다
        Comparator<Item> byPopularity = Comparator
                .comparingLong((Item item) -> item.model().getReactionCount()).reversed()
                .thenComparing(item -> item.share().getViewCount(), Comparator.reverseOrder())
                .thenComparing(item -> item.share().getCreatedAt(), Comparator.reverseOrder());
        List<Item> ordered = new ArrayList<>(items.stream().sorted(byPopularity).limit(POPULAR_LIMIT).toList());
        items.stream()
                .sorted(Comparator.comparing((Item item) -> item.share().getCreatedAt(), Comparator.reverseOrder()))
                .filter(item -> !ordered.contains(item))
                .limit(GALLERY_LIMIT - ordered.size())
                .forEach(ordered::add);
        return ordered.stream()
                .map(item -> new GalleryShareResponse(
                        item.share().getShareToken(),
                        item.model().getName(),
                        item.model().getDescription(),
                        item.model().getDatabaseType(),
                        item.model().getUpdatedAt(),
                        item.share().getCreatedAt(),
                        item.model().getReactionCount(),
                        item.share().getViewCount()))
                .toList();
    }

    /** 공유 문서 목록 한 페이지 (08-core/12-share-feedback.md Section 1.5.1) */
    public record GalleryPage(List<GalleryShareResponse> items, long totalCount) {
    }

    /**
     * 공유 문서 목록(무인증) — 갤러리와 같은 후보(활성 링크, 문서당 최신 1건)를 검색어로 거르고 정렬해 한 페이지를 내려준다.
     * 랜딩의 "더보기"가 여는 목록 화면이 쓴다. 검색은 문서 이름과 설명의 부분 일치(대소문자 무시)다.
     *
     * @param query 검색어 — null·빈 문자열이면 전체
     * @param sort  recent(최근 공유순, 기본) 또는 popular(반응 수 → 조회 수 → 최근 공유순)
     */
    @Transactional(readOnly = true)
    public GalleryPage browse(String query, String sort, int page, int size) {
        Instant now = Instant.now();
        Map<Long, ModelShare> latestByModel = new LinkedHashMap<>();
        for (ModelShare share : shareRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            if (isActive(share, now)) {
                latestByModel.putIfAbsent(share.getModelId(), share);
            }
        }
        if (latestByModel.isEmpty()) {
            return new GalleryPage(List.of(), 0);
        }
        Map<Long, Model> models = modelRepository.findAllById(latestByModel.keySet()).stream()
                .collect(Collectors.toMap(Model::getId, Function.identity()));
        String needle = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        List<GalleryShareResponse> matched = new ArrayList<>();
        for (Map.Entry<Long, ModelShare> entry : latestByModel.entrySet()) {
            Model model = models.get(entry.getKey());
            if (model == null) {
                continue;
            }
            String haystack = (model.getName() + "\n" + (model.getDescription() == null ? "" : model.getDescription()))
                    .toLowerCase(java.util.Locale.ROOT);
            if (!needle.isEmpty() && !haystack.contains(needle)) {
                continue;
            }
            ModelShare share = entry.getValue();
            matched.add(new GalleryShareResponse(share.getShareToken(), model.getName(), model.getDescription(),
                    model.getDatabaseType(), model.getUpdatedAt(), share.getCreatedAt(), model.getReactionCount(), share.getViewCount()));
        }
        // 후보는 이미 최근 공유순이다 — 인기순일 때만 다시 정렬한다(안정 정렬이라 동률은 최근 공유순으로 남는다)
        if ("popular".equals(sort)) {
            matched.sort(Comparator.comparingLong(GalleryShareResponse::reactionCount).reversed()
                    .thenComparing(GalleryShareResponse::viewCount, Comparator.reverseOrder()));
        }
        int from = Math.min((page - 1) * size, matched.size());
        int to = Math.min(from + size, matched.size());
        return new GalleryPage(List.copyOf(matched.subList(from, to)), matched.size());
    }

    /**
     * 사이트맵 원료(무인증, 08-core/02-model.md Section 1.10.10) — 활성 공유 문서 전부를
     * 문서당 최신 링크 토큰 + 문서 갱신시각(lastmod)으로 내려준다(빌드 시 sitemap.xml 생성이
     * 소비). 상한 {@value SITEMAP_LIMIT}건 — 초과분은 최신 순으로 잘리고 경고 로그.
     * 저장소 단일 쿼리(조인 프로젝션)라 문서 수만큼의 추가 조회가 없다.
     */
    @Transactional(readOnly = true)
    public List<SitemapShareResponse> sitemap() {
        List<ShareQueryRepository.SitemapRow> rows =
                shareQueryRepository.findSitemapShares(Instant.now(), SITEMAP_LIMIT + 1);
        if (rows.size() > SITEMAP_LIMIT) {
            log.warn("사이트맵 상한 초과 — 활성 공유 문서 {}건 중 최신 {}건만 내려준다", rows.size(), SITEMAP_LIMIT);
            rows = rows.subList(0, SITEMAP_LIMIT);
        }
        return rows.stream()
                .map(row -> new SitemapShareResponse(row.shareToken(), row.lastmod()))
                .toList();
    }

    /**
     * 링크 기간 판정 — 시작일 null은 즉시, 종료일 null은 무제한. 피드백 경로(1.10.6·1.10.7)가
     * 같은 판정(404/410)으로 재사용한다.
     */
    static boolean isActive(ModelShare share, Instant now) {
        return (share.getStartsAt() == null || !now.isBefore(share.getStartsAt()))
                && (share.getEndsAt() == null || !now.isAfter(share.getEndsAt()));
    }

    /** 링크 응답 조립 — 조회 수는 링크 고유, 반응·댓글 수는 문서 단위 값(2026-09-28 — 1.10.6·1.10.7) */
    private static ModelShareResponse toResponse(ModelShare share, Model model) {
        return new ModelShareResponse(
                Long.toString(share.getId()),
                share.getShareToken(),
                share.getStartsAt(),
                share.getEndsAt(),
                share.getCreatedAt(),
                share.getViewCount(),
                model.getReactionCount(),
                model.getCommentCount());
    }
}
