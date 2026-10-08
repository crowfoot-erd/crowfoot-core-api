package net.java21.crowfoot.api.showcase.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.model.service.ShareService;
import net.java21.crowfoot.api.showcase.client.CaptureClient;
import net.java21.crowfoot.api.showcase.client.CaptureException;
import net.java21.crowfoot.api.showcase.client.CaptureResult;
import net.java21.crowfoot.api.showcase.domain.SiteShowcase;
import net.java21.crowfoot.api.showcase.domain.SiteShowcaseReport;
import net.java21.crowfoot.api.showcase.dto.AdminSiteResponse;
import net.java21.crowfoot.api.showcase.dto.PublicSiteResponse;
import net.java21.crowfoot.api.showcase.dto.SaveSiteRequest;
import net.java21.crowfoot.api.showcase.dto.SiteResponse;
import net.java21.crowfoot.api.showcase.repository.SiteShowcaseReportRepository;
import net.java21.crowfoot.api.showcase.repository.SiteShowcaseRepository;
import net.java21.crowfoot.api.showcase.repository.SiteSummary;
import net.java21.crowfoot.api.showcase.repository.SiteThumbnail;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사이트 쇼케이스 (08-core/19-site-showcase.md) — 문서의 사이트 등록·다시 가져오기·삭제, 공개 목록·썸네일·신고, 관리자 숨김.
 *
 * <p>캡처 서비스 호출(최대 약 35초)은 DB 트랜잭션 밖에서 한다 — 커넥션을 붙잡지 않게 권한 확인·조회와 저장을 따로 연다.
 */
@Service
@RequiredArgsConstructor
public class SiteShowcaseService {

    /** 다시 가져오기 간격(Section 3.3) */
    static final Duration CAPTURE_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_PUBLIC_SIZE = 48;
    private static final int MAX_ADMIN_SIZE = 100;
    private static final String THUMBNAIL_PATH = "/api/v1/core/showcase/sites/%d/thumbnail?v=%d";

    private final SiteShowcaseRepository showcaseRepository;
    private final SiteShowcaseReportRepository reportRepository;
    private final ModelRepository modelRepository;
    private final ModelShareRepository shareRepository;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;
    private final CaptureClient captureClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /* ---------- 문서의 사이트 (Section 3.1~3.4) ---------- */

    /** 조회 — 멤버. 없으면 null */
    @Transactional(readOnly = true)
    public SiteResponse get(long userId, long workspaceId, long modelId) {
        roleChecker.requireMember(userId, workspaceId);
        requireModel(workspaceId, modelId);
        return showcaseRepository.findByModelId(modelId).map(SiteShowcaseService::toResponse).orElse(null);
    }

    /** 등록·수정 — Editor 이상. 처음이거나 주소가 바뀌면 캡처한다 */
    public SiteResponse save(long userId, long workspaceId, long modelId, SaveSiteRequest request) {
        String url = normalizeUrl(request.url());
        SiteShowcase existing = transactionTemplate.execute(status -> {
            roleChecker.requireEditor(userId, workspaceId);
            requireModel(workspaceId, modelId);
            return showcaseRepository.findByModelId(modelId).orElse(null);
        });
        boolean newUrl = existing == null || !existing.getUrl().equals(url);
        // 주소가 바뀌었는데 지금 제목·설명이 그대로 왔으면 손대지 않은 값이다 — 화면이 입력 칸을 미리 채워 보낸다.
        // 예전 사이트의 값이므로 새로 가져온 값을 쓴다(v1.41 — 첫 캡처 실패로 남은 호스트 제목이 github.com에 붙었던 일)
        boolean carriedOver = newUrl && existing != null;
        String title = carriedOver && Objects.equals(blankToNull(request.title()), existing.getTitle())
                ? null : blankToNull(request.title());
        // description은 null(생략)과 ""(지움)이 다르다 — 예전 사이트의 설명이 그대로 왔을 때만 생략으로 본다
        String description = carriedOver && request.description() != null
                && Objects.equals(blankToNull(request.description()), existing.getDescription())
                && existing.getDescription() != null ? null : request.description();
        CaptureResult captured = null;
        String captureError = null;
        if (newUrl) {
            try {
                captured = captureClient.capture(url);
            } catch (CaptureException e) {
                if (e.rejectsUrl()) {
                    throw BusinessException.of(ErrorCode.SITE_URL_BLOCKED, "detail.showcase.url-blocked");
                }
                captureError = e.getMessage();
            }
        }
        Instant now = clock.instant();
        CaptureResult result = captured;
        String error = captureError;
        SiteShowcase saved = transactionTemplate.execute(status -> {
            SiteShowcase site = showcaseRepository.findByModelId(modelId)
                    .orElseGet(() -> new SiteShowcase(modelId, url, hostOf(url), userId));
            if (newUrl) {
                site.setUrl(url);
                site.setCaptureAttemptedAt(now);
                if (result != null) {
                    applyCapture(site, result, now);
                    site.setTitle(firstNonNull(title, truncate(result.title(), 200), hostOf(url)));
                    site.setDescription(description != null ? blankToNull(description)
                            : truncate(result.description(), 500));
                } else {
                    site.clearCapture();
                    site.setCaptureError(truncate(error, 300));
                    site.setTitle(firstNonNull(title, hostOf(url)));
                    site.setDescription(blankToNull(description));
                }
            } else {
                if (title != null) {
                    site.setTitle(title);
                }
                if (description != null) {
                    site.setDescription(blankToNull(description));
                }
            }
            return showcaseRepository.save(site);
        });
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("url", url);
        detail.put("captured", result != null);
        auditRecorder.record(userId, "SITE_SHOWCASE_SAVED", "MODEL", String.valueOf(modelId), detail);
        return toResponse(saved);
    }

    /**
     * 다시 가져오기 — Editor 이상, 1분에 한 번. 실패는 captureError로 알린다. 제목·설명은 두고 썸네일·캡처 메타만 바꾸되,
     * 제목이 대체값(호스트)이거나 설명이 비어 있으면 캡처 값으로 채운다
     */
    public SiteResponse recapture(long userId, long workspaceId, long modelId) {
        SiteShowcase site = transactionTemplate.execute(status -> {
            roleChecker.requireEditor(userId, workspaceId);
            requireModel(workspaceId, modelId);
            return showcaseRepository.findByModelId(modelId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SITE_SHOWCASE_NOT_FOUND));
        });
        Instant now = clock.instant();
        if (site.getCaptureAttemptedAt() != null && site.getCaptureAttemptedAt().plus(CAPTURE_INTERVAL).isAfter(now)) {
            throw BusinessException.of(ErrorCode.SITE_CAPTURE_TOO_SOON, "detail.showcase.capture-too-soon");
        }
        CaptureResult captured = null;
        String captureError = null;
        try {
            captured = captureClient.capture(site.getUrl());
        } catch (CaptureException e) {
            captureError = e.getMessage();
        }
        CaptureResult result = captured;
        String error = captureError;
        SiteShowcase saved = transactionTemplate.execute(status -> {
            SiteShowcase current = showcaseRepository.findByModelId(modelId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SITE_SHOWCASE_NOT_FOUND));
            current.setCaptureAttemptedAt(now);
            if (result != null) {
                applyCapture(current, result, now);
                // 첫 캡처가 실패해 대체값(호스트·빈 설명)으로 등록된 경우만 채운다 — 사용자가 고친 값은 두지 않는다
                if (current.getTitle().equals(hostOf(current.getUrl())) && result.title() != null) {
                    current.setTitle(truncate(result.title(), 200));
                }
                if (current.getDescription() == null && result.description() != null) {
                    current.setDescription(truncate(result.description(), 500));
                }
            } else {
                current.setCaptureError(truncate(error, 300));
            }
            return showcaseRepository.save(current);
        });
        return toResponse(saved);
    }

    /** 삭제 — Editor 이상. 신고 기록도 함께 지운다 */
    @Transactional
    public void delete(long userId, long workspaceId, long modelId) {
        roleChecker.requireEditor(userId, workspaceId);
        requireModel(workspaceId, modelId);
        SiteShowcase site = showcaseRepository.findByModelId(modelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_SHOWCASE_NOT_FOUND));
        reportRepository.deleteByShowcaseId(site.getId());
        showcaseRepository.delete(site);
        auditRecorder.record(userId, "SITE_SHOWCASE_REMOVED", "MODEL", String.valueOf(modelId),
                Map.of("url", site.getUrl()));
    }

    /* ---------- 공개 (Section 3.5~3.7) ---------- */

    /** 공개 목록 — 숨기지 않은 사이트, 최근 등록순 */
    @Transactional(readOnly = true)
    public Page<PublicSiteResponse> publicList(int page, int size) {
        Page<SiteSummary> sites = showcaseRepository.findVisible(
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PUBLIC_SIZE)));
        Map<Long, Model> models = modelsOf(sites.getContent());
        Map<Long, String> tokens = activeShareTokens(models.keySet());
        return sites.map(site -> {
            Model model = models.get(site.modelId());
            return new PublicSiteResponse(String.valueOf(site.id()), site.url(), site.title(), site.description(),
                    site.siteName(), site.faviconUrl(), thumbnailUrl(site.id(), site.thumbnailType(), site.capturedAt()),
                    model == null ? null : model.getName(), model == null ? null : model.getDatabaseType(),
                    tokens.get(site.modelId()), site.createdAt());
        });
    }

    /** 썸네일 — 숨긴·없는 사이트는 404 */
    @Transactional(readOnly = true)
    public SiteThumbnail thumbnail(long siteId) {
        return showcaseRepository.findVisibleThumbnail(siteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_SHOWCASE_NOT_FOUND));
    }

    /** 신고 — 로그인 사용자, 멱등. 3건이 되면 자동 숨김 */
    @Transactional
    public void report(long userId, long siteId, String reason) {
        SiteShowcase site = showcaseRepository.findById(siteId)
                .filter(found -> !found.isHidden())
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_SHOWCASE_NOT_FOUND));
        if (reportRepository.existsByShowcaseIdAndUserId(siteId, userId)) {
            return;
        }
        try {
            reportRepository.saveAndFlush(new SiteShowcaseReport(siteId, userId, blankToNull(reason)));
        } catch (DataIntegrityViolationException e) {
            return; // 같은 사용자의 동시 신고 — 유일 제약이 막았다
        }
        showcaseRepository.incrementReportCount(siteId);
        SiteShowcase counted = showcaseRepository.findById(siteId).orElse(site);
        boolean autoHidden = false;
        if (!counted.isHidden() && counted.getReportCount() >= SiteShowcase.AUTO_HIDE_REPORTS) {
            counted.setHidden(true);
            counted.setHiddenAt(clock.instant());
            autoHidden = true;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reportCount", counted.getReportCount());
        if (autoHidden) {
            detail.put("autoHidden", true);
        }
        auditRecorder.record(userId, "SITE_SHOWCASE_REPORTED", "SITE_SHOWCASE", String.valueOf(siteId), detail);
    }

    /* ---------- 관리자 (Section 3.8·3.9) ---------- */

    @Transactional(readOnly = true)
    public Page<AdminSiteResponse> adminList(long adminId, Boolean hidden, int page, int size) {
        adminGuard.requireAdmin(adminId);
        Page<SiteSummary> sites = showcaseRepository.findForAdmin(hidden,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_ADMIN_SIZE)));
        Map<Long, Model> models = modelsOf(sites.getContent());
        Set<Long> userIds = sites.getContent().stream().map(SiteSummary::createdBy).collect(Collectors.toSet());
        Map<Long, String> names = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        auditRecorder.record(adminId, "ADMIN_SITE_SHOWCASES_LISTED", "SITE_SHOWCASE", "ALL", null);
        return sites.map(site -> {
            Model model = models.get(site.modelId());
            return new AdminSiteResponse(String.valueOf(site.id()), site.url(), site.title(), site.description(),
                    site.siteName(), site.faviconUrl(), thumbnailUrl(site.id(), site.thumbnailType(), site.capturedAt()),
                    model == null ? null : model.getName(), model == null ? null : model.getDatabaseType(),
                    model == null ? null : String.valueOf(model.getWorkspaceId()), String.valueOf(site.modelId()),
                    names.get(site.createdBy()), site.reportCount(), site.hidden(), site.hiddenAt(),
                    site.hidden() && site.hiddenBy() == null, site.captureError(), site.createdAt());
        });
    }

    /** 숨김·보임 — 다시 보이게 하면 신고 수와 기록을 비운다 */
    @Transactional
    public void setHidden(long adminId, long siteId, boolean hidden) {
        adminGuard.requireAdmin(adminId);
        SiteShowcase site = showcaseRepository.findById(siteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_SHOWCASE_NOT_FOUND));
        if (hidden) {
            site.setHidden(true);
            site.setHiddenBy(adminId);
            site.setHiddenAt(clock.instant());
        } else {
            site.setHidden(false);
            site.setHiddenBy(null);
            site.setHiddenAt(null);
            site.setReportCount(0);
            reportRepository.deleteByShowcaseId(siteId);
        }
        auditRecorder.record(adminId, hidden ? "SITE_SHOWCASE_HIDDEN" : "SITE_SHOWCASE_UNHIDDEN",
                "SITE_SHOWCASE", String.valueOf(siteId), null);
    }

    /* ---------- 공용 ---------- */

    private void requireModel(long workspaceId, long modelId) {
        modelRepository.findByIdAndWorkspaceId(modelId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND));
    }

    private static void applyCapture(SiteShowcase site, CaptureResult result, Instant now) {
        site.setThumbnail(result.image());
        site.setThumbnailType(result.image() == null ? null : firstNonNull(result.imageType(), "image/jpeg"));
        site.setCapturedAt(result.image() == null ? null : now);
        site.setSiteName(truncate(result.siteName(), 100));
        site.setFaviconUrl(truncate(result.faviconUrl(), 2000));
        site.setCaptureError(null);
    }

    private Map<Long, Model> modelsOf(List<SiteSummary> sites) {
        Set<Long> modelIds = sites.stream().map(SiteSummary::modelId).collect(Collectors.toSet());
        return modelIds.isEmpty() ? Map.of() : modelRepository.findAllById(modelIds).stream()
                .collect(Collectors.toMap(Model::getId, Function.identity()));
    }

    /** 문서마다 가장 최근 발급한 활성 공유 링크의 토큰 — 공개 갤러리와 같은 규칙(08-core/12-share-feedback.md Section 1.5) */
    private Map<Long, String> activeShareTokens(Set<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
        Map<Long, String> tokens = new HashMap<>();
        for (ModelShare share : shareRepository.findByModelIdInOrderByCreatedAtDescIdDesc(modelIds)) {
            if (ShareService.isActive(share, now)) {
                tokens.putIfAbsent(share.getModelId(), share.getShareToken());
            }
        }
        return tokens;
    }

    static SiteResponse toResponse(SiteShowcase site) {
        return new SiteResponse(String.valueOf(site.getId()), site.getUrl(), site.getTitle(), site.getDescription(),
                site.getSiteName(), site.getFaviconUrl(),
                thumbnailUrl(site.getId(), site.getThumbnailType(), site.getCapturedAt()),
                site.getCapturedAt(), site.getCaptureError(), site.isHidden(), site.getReportCount(),
                site.getCreatedAt(), site.getUpdatedAt());
    }

    /** 썸네일 주소 — v는 captured_at 초 단위, 다시 찍으면 바뀌어 캐시를 우회한다(Section 3.1) */
    static String thumbnailUrl(Long siteId, String thumbnailType, Instant capturedAt) {
        if (thumbnailType == null || capturedAt == null) {
            return null;
        }
        return THUMBNAIL_PATH.formatted(siteId, capturedAt.getEpochSecond());
    }

    /** 주소 검사 — http·https, 호스트 있음, 사용자 정보 없음, 2,000자 이하(Section 3.2). 앞뒤 공백만 지운다 */
    static String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.strip();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (url.length() > 2000 || !(scheme.equals("http") || scheme.equals("https"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.showcase.url-invalid");
            }
        } catch (URISyntaxException e) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.showcase.url-invalid");
        }
        return url;
    }

    static String hostOf(String url) {
        String host = URI.create(url).getHost();
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
