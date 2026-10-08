package net.java21.crowfoot.api.showcase.service;

import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.showcase.client.CaptureClient;
import net.java21.crowfoot.api.showcase.client.CaptureException;
import net.java21.crowfoot.api.showcase.client.CaptureResult;
import net.java21.crowfoot.api.showcase.domain.SiteShowcase;
import net.java21.crowfoot.api.showcase.dto.PublicSiteResponse;
import net.java21.crowfoot.api.showcase.dto.SaveSiteRequest;
import net.java21.crowfoot.api.showcase.dto.SiteResponse;
import net.java21.crowfoot.api.showcase.repository.SiteShowcaseReportRepository;
import net.java21.crowfoot.api.showcase.repository.SiteShowcaseRepository;
import net.java21.crowfoot.api.showcase.repository.SiteSummary;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 사이트 쇼케이스 단위 테스트 (08-core/19-site-showcase.md Section 3) — 등록(캡처 값·사용자 값 우선·실패해도 저장·
 * 내부망 거절·같은 주소면 캡처 생략·주소가 바뀌었는데 실패하면 이전 그림 지움), 다시 가져오기(1분 제한·실패 시 그림 유지),
 * 공개 목록의 공유 토큰, 신고(멱등·3건 자동 숨김), 관리자 보이기(신고 초기화).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SiteShowcaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T08:00:00Z");
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 1};

    @Mock
    private SiteShowcaseRepository showcaseRepository;
    @Mock
    private SiteShowcaseReportRepository reportRepository;
    @Mock
    private ModelRepository modelRepository;
    @Mock
    private ModelShareRepository shareRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private CaptureClient captureClient;
    @Mock
    private TransactionTemplate transactionTemplate;

    private SiteShowcaseService service;
    private SiteShowcase stored;

    @BeforeEach
    void setUp() {
        service = new SiteShowcaseService(showcaseRepository, reportRepository, modelRepository, shareRepository,
                userRepository, roleChecker, adminGuard, auditRecorder, captureClient, transactionTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC));
        given(transactionTemplate.execute(any())).willAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        Model model = new Model(77L, "blog 1.0", null, "mysql", "{}", 7L);
        ReflectionTestUtils.setField(model, "id", 501L);
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
        given(showcaseRepository.findByModelId(501L)).willAnswer(inv -> Optional.ofNullable(stored));
        given(showcaseRepository.save(any(SiteShowcase.class))).willAnswer(inv -> {
            stored = inv.getArgument(0);
            if (stored.getId() == null) {
                ReflectionTestUtils.setField(stored, "id", 12L);
            }
            return stored;
        });
    }

    private static CaptureResult captured(String title) {
        return new CaptureResult("https://blog.example.com/", title, "개발 이야기", "Example",
                "https://blog.example.com/favicon.ico", "image/jpeg", JPEG);
    }

    @Test
    @DisplayName("처음 등록하면 캡처 값으로 채우고, 사용자가 적은 제목이 우선한다")
    void saveNewWithCapture() {
        given(captureClient.capture("https://blog.example.com")).willReturn(captured("캡처 제목"));

        SiteResponse response = service.save(2L, 77L, 501L, new SaveSiteRequest(" https://blog.example.com ", "내 블로그", null));

        assertThat(response.title()).isEqualTo("내 블로그");
        assertThat(response.description()).isEqualTo("개발 이야기");
        assertThat(response.siteName()).isEqualTo("Example");
        assertThat(response.thumbnailUrl()).isEqualTo("/api/v1/core/showcase/sites/12/thumbnail?v=" + NOW.getEpochSecond());
        assertThat(stored.getThumbnail()).isEqualTo(JPEG);
        assertThat(stored.getCaptureAttemptedAt()).isEqualTo(NOW);
        verify(roleChecker).requireEditor(2L, 77L);
        verify(auditRecorder).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("캡처가 실패해도 저장한다 — 제목은 호스트, 실패 사유를 남긴다. 내부망 주소는 저장하지 않고 거절한다")
    void saveWhenCaptureFails() {
        given(captureClient.capture("https://www.broken.example")).willThrow(new CaptureException("CAPTURE_FAILED", "인증서 오류"));
        SiteResponse response = service.save(2L, 77L, 501L, new SaveSiteRequest("https://www.broken.example", null, null));
        assertThat(response.title()).isEqualTo("broken.example");
        assertThat(response.captureError()).isEqualTo("인증서 오류");
        assertThat(response.thumbnailUrl()).isNull();

        stored = null;
        given(captureClient.capture("http://10.0.0.5")).willThrow(new CaptureException(CaptureException.BLOCKED_ADDRESS, "blocked"));
        assertThatThrownBy(() -> service.save(2L, 77L, 501L, new SaveSiteRequest("http://10.0.0.5", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SITE_URL_BLOCKED);
        assertThat(stored).isNull();
    }

    @Test
    @DisplayName("주소 형식이 틀리면 400 — 스킴·사용자 정보·호스트")
    void invalidUrl() {
        for (String url : List.of("ftp://example.com", "https://user:pw@example.com", "example.com", "https://")) {
            assertThatThrownBy(() -> service.save(2L, 77L, 501L, new SaveSiteRequest(url, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        }
        verify(captureClient, never()).capture(anyString());
    }

    @Test
    @DisplayName("같은 주소면 캡처하지 않고 제목·설명만 — 빈 제목은 두고, 빈 문자열 설명은 지운다. 주소가 바뀌었는데 실패하면 이전 그림을 지운다")
    void sameUrlAndChangedUrl() {
        given(captureClient.capture("https://blog.example.com")).willReturn(captured("캡처 제목"));
        service.save(2L, 77L, 501L, new SaveSiteRequest("https://blog.example.com", null, null));

        SiteResponse edited = service.save(2L, 77L, 501L, new SaveSiteRequest("https://blog.example.com", " ", ""));
        assertThat(edited.title()).isEqualTo("캡처 제목");
        assertThat(edited.description()).isNull();
        verify(captureClient).capture("https://blog.example.com");

        given(captureClient.capture("https://new.example.com")).willThrow(new CaptureException("CAPTURE_FAILED", "시간 초과"));
        SiteResponse moved = service.save(2L, 77L, 501L, new SaveSiteRequest("https://new.example.com", null, null));
        assertThat(moved.thumbnailUrl()).isNull();
        assertThat(moved.siteName()).isNull();
        assertThat(moved.title()).isEqualTo("new.example.com");
    }

    @Test
    @DisplayName("다시 가져오기는 1분에 한 번, 실패하면 이전 그림을 두고 사유만 바꾼다")
    void recapture() {
        given(captureClient.capture("https://blog.example.com")).willReturn(captured("캡처 제목"));
        service.save(2L, 77L, 501L, new SaveSiteRequest("https://blog.example.com", "내 제목", null));

        assertThatThrownBy(() -> service.recapture(2L, 77L, 501L))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SITE_CAPTURE_TOO_SOON);

        stored.setCaptureAttemptedAt(NOW.minusSeconds(61));
        given(captureClient.capture("https://blog.example.com")).willThrow(new CaptureException("CAPTURE_BUSY", "바쁨"));
        SiteResponse response = service.recapture(2L, 77L, 501L);
        assertThat(response.captureError()).isEqualTo("바쁨");
        assertThat(response.thumbnailUrl()).isNotNull();
        assertThat(response.title()).isEqualTo("내 제목");
    }

    @Test
    @DisplayName("첫 캡처가 실패해 호스트 제목으로 등록됐으면, 다시 가져오기가 성공할 때 캡처한 제목·설명으로 채운다")
    void recaptureFillsFallbackTitle() {
        given(captureClient.capture("https://www.blog.example.com")).willThrow(new CaptureException("CAPTURE_FAILED", "시간 초과"));
        service.save(2L, 77L, 501L, new SaveSiteRequest("https://www.blog.example.com", null, null));
        assertThat(stored.getTitle()).isEqualTo("blog.example.com");

        stored.setCaptureAttemptedAt(NOW.minusSeconds(61));
        willReturn(captured("예제 블로그")).given(captureClient).capture("https://www.blog.example.com");
        SiteResponse response = service.recapture(2L, 77L, 501L);

        assertThat(response.title()).isEqualTo("예제 블로그");
        assertThat(response.description()).isEqualTo("개발 이야기");
        assertThat(response.captureError()).isNull();
    }

    @Test
    @DisplayName("공개 목록은 활성 공유 링크가 있는 문서에만 shareToken을 싣는다")
    void publicListShareToken() {
        SiteSummary shared = new SiteSummary(12L, 501L, "https://a.example", "A", null, null, null, "image/jpeg",
                NOW, null, false, null, null, 0, 2L, NOW);
        SiteSummary unshared = new SiteSummary(13L, 502L, "https://b.example", "B", null, null, null, null,
                null, null, false, null, null, 0, 2L, NOW);
        given(showcaseRepository.findVisible(any())).willReturn(new PageImpl<>(List.of(shared, unshared), PageRequest.of(0, 12), 2));
        Model other = new Model(78L, "shop", null, "postgresql", "{}", 7L);
        ReflectionTestUtils.setField(other, "id", 502L);
        Model blog = modelRepository.findByIdAndWorkspaceId(501L, 77L).orElseThrow();
        given(modelRepository.findAllById(any())).willReturn(List.of(blog, other));
        given(shareRepository.findByModelIdInOrderByCreatedAtDescIdDesc(any())).willReturn(List.of(
                new ModelShare(501L, "tok-active", null, null, 2L),
                new ModelShare(502L, "tok-expired", null, NOW.minusSeconds(10), 2L)));

        Page<PublicSiteResponse> page = service.publicList(0, 12);

        assertThat(page.getContent()).extracting(PublicSiteResponse::shareToken).containsExactly("tok-active", null);
        assertThat(page.getContent()).extracting(PublicSiteResponse::modelName).containsExactly("blog 1.0", "shop");
        assertThat(page.getContent().get(1).thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("신고는 같은 사용자면 멱등, 3건째에 자동 숨김(hiddenBy 없음)")
    void reportAutoHide() {
        SiteShowcase site = new SiteShowcase(501L, "https://a.example", "A", 2L);
        ReflectionTestUtils.setField(site, "id", 12L);
        site.setReportCount(2);
        given(showcaseRepository.findById(12L)).willReturn(Optional.of(site));
        given(reportRepository.existsByShowcaseIdAndUserId(12L, 5L)).willReturn(false);
        given(showcaseRepository.incrementReportCount(12L)).willAnswer(inv -> {
            site.setReportCount(site.getReportCount() + 1);
            return 1;
        });

        service.report(5L, 12L, "광고");
        assertThat(site.isHidden()).isTrue();
        assertThat(site.getHiddenBy()).isNull();

        given(reportRepository.existsByShowcaseIdAndUserId(12L, 6L)).willReturn(true);
        site.setHidden(false);
        service.report(6L, 12L, null);
        verify(showcaseRepository).incrementReportCount(anyLong());
    }

    @Test
    @DisplayName("관리자가 다시 보이게 하면 신고 수와 기록을 비운다")
    void unhideResetsReports() {
        SiteShowcase site = new SiteShowcase(501L, "https://a.example", "A", 2L);
        ReflectionTestUtils.setField(site, "id", 12L);
        site.setHidden(true);
        site.setReportCount(3);
        given(showcaseRepository.findById(12L)).willReturn(Optional.of(site));

        service.setHidden(1L, 12L, false);

        assertThat(site.isHidden()).isFalse();
        assertThat(site.getReportCount()).isZero();
        verify(adminGuard).requireAdmin(1L);
        verify(reportRepository).deleteByShowcaseId(12L);
    }
}
