package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelShare;
import net.java21.crowfoot.api.model.dto.CreateShareRequest;
import net.java21.crowfoot.api.model.dto.GalleryShareResponse;
import net.java21.crowfoot.api.model.dto.ModelShareResponse;
import net.java21.crowfoot.api.model.dto.PublicShareResponse;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelShareRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 문서 공유 링크 API 단위 테스트 (08-core/02-model.md Section 1.10) —
 * 발급(기간 검증·토큰 생성)·목록·철회·공개 조회(기간 밖 410·토큰 없음 404)·
 * 공개 갤러리(활성만·문서당 최근 링크 1개·갱신순)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ShareServiceTest {

    private static final Instant PAST = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    @Mock
    private ModelShareRepository shareRepository;
    @Mock
    private ModelRepository modelRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;
    @Spy
    private ShareTokenGenerator tokenGenerator = new ShareTokenGenerator();
    @InjectMocks
    private ShareService shareService;

    @Test
    @DisplayName("발급은 무기간(둘 다 null)이면 startsAt·endsAt 없이 저장하고 감사 로그를 남긴다")
    void createUnlimitedShare() {
        // given
        Model model = new Model(77L, "주문 ERD", null, "postgresql", "{}", 7L);
        ReflectionTestUtils.setField(model, "id", 501L);
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
        given(shareRepository.existsByShareToken(anyString())).willReturn(false);
        given(shareRepository.save(any(ModelShare.class)))
                .willAnswer(inv -> {
                    ModelShare share = inv.getArgument(0);
                    ReflectionTestUtils.setField(share, "id", 9L);
                    return share;
                });

        // when
        ModelShareResponse response = shareService.create(7L, 77L, 501L, new CreateShareRequest(null, null));

        // then
        assertThat(response.shareToken()).hasSize(22).matches("[A-Za-z0-9]+");
        assertThat(response.startsAt()).isNull();
        assertThat(response.endsAt()).isNull();
        ArgumentCaptor<ModelShare> captor = ArgumentCaptor.forClass(ModelShare.class);
        then(shareRepository).should().save(captor.capture());
        assertThat(captor.getValue().getModelId()).isEqualTo(501L);
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(7L);
        then(auditRecorder).should().record(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("발급 요청의 종료일이 시작일보다 앞서면 400 INVALID_REQUEST이다")
    void createRejectsReversedPeriod() {
        assertThatThrownBy(() -> shareService.create(7L, 77L, 501L,
                new CreateShareRequest(FUTURE, PAST)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        then(shareRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("발급 대상 문서가 없으면 404 MODEL_NOT_FOUND이다")
    void createRejectsMissingModel() {
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> shareService.create(7L, 77L, 501L, new CreateShareRequest(null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MODEL_NOT_FOUND);
    }

    @Test
    @DisplayName("목록은 문서의 링크를 그대로 매핑해 내려준다")
    void listMapsShares() {
        Model model = new Model(77L, "주문 ERD", null, "postgresql", "{}", 7L);
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
        ModelShare share = new ModelShare(501L, "tok123", PAST, FUTURE, 7L);
        ReflectionTestUtils.setField(share, "id", 9L);
        given(shareRepository.findByModelIdOrderByCreatedAtDescIdDesc(501L)).willReturn(List.of(share));

        List<ModelShareResponse> responses = shareService.list(7L, 77L, 501L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).shareToken()).isEqualTo("tok123");
        assertThat(responses.get(0).startsAt()).isEqualTo(PAST);
        assertThat(responses.get(0).endsAt()).isEqualTo(FUTURE);
    }

    @Test
    @DisplayName("철회는 문서 경계 안에서 삭제하고, 링크가 없으면 404 SHARE_NOT_FOUND이다")
    void revokeDeletesWithinModelBoundary() {
        Model model = new Model(77L, "주문 ERD", null, "postgresql", "{}", 7L);
        given(modelRepository.findByIdAndWorkspaceId(501L, 77L)).willReturn(Optional.of(model));
        ModelShare share = new ModelShare(501L, "tok123", null, null, 7L);
        given(shareRepository.findByIdAndModelId(9L, 501L)).willReturn(Optional.of(share));

        shareService.revoke(7L, 77L, 501L, 9L);

        then(shareRepository).should().deleteByIdAndModelId(9L, 501L);
        given(shareRepository.findByIdAndModelId(8L, 501L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> shareService.revoke(7L, 77L, 501L, 8L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHARE_NOT_FOUND);
        then(shareRepository).should(never()).deleteByIdAndModelId(8L, 501L);
    }

    @Test
    @DisplayName("공개 조회는 기간 내면 문서 메타와 content를 내려준다")
    void resolveReturnsDocumentWithinPeriod() {
        ModelShare share = new ModelShare(501L, "tok123", PAST, FUTURE, 7L);
        given(shareRepository.findByShareToken("tok123")).willReturn(Optional.of(share));
        Model model = new Model(77L, "주문 ERD", "설명", "postgresql", "{\"tables\":[]}", 7L);
        given(modelRepository.findById(501L)).willReturn(Optional.of(model));

        PublicShareResponse response = shareService.resolve("tok123");

        assertThat(response.modelName()).isEqualTo("주문 ERD");
        assertThat(response.databaseType()).isEqualTo("postgresql");
        assertThat(response.content()).isEqualTo("{\"tables\":[]}");
        assertThat(response.startsAt()).isEqualTo(PAST);
        assertThat(response.endsAt()).isEqualTo(FUTURE);
    }

    @Test
    @DisplayName("공개 조회는 시작 전·종료 후면 410 SHARE_INACTIVE다")
    void resolveRejectsOutsidePeriod() {
        ModelShare beforeStart = new ModelShare(501L, "tok1", FUTURE, null, 7L);
        given(shareRepository.findByShareToken("tok1")).willReturn(Optional.of(beforeStart));
        ModelShare afterEnd = new ModelShare(501L, "tok2", null, PAST, 7L);
        given(shareRepository.findByShareToken("tok2")).willReturn(Optional.of(afterEnd));

        for (String token : List.of("tok1", "tok2")) {
            assertThatThrownBy(() -> shareService.resolve(token))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SHARE_INACTIVE);
        }
        then(modelRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("공개 조회는 토큰이 없으면 404 SHARE_NOT_FOUND다")
    void resolveRejectsUnknownToken() {
        given(shareRepository.findByShareToken("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> shareService.resolve("nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHARE_NOT_FOUND);
    }

    @Test
    @DisplayName("갤러리는 활성 링크만, 문서당 최근 링크 1개씩, 문서 갱신순으로 메타를 내려준다")
    void galleryListsActiveSharesDedupedByModel() {
        // given — 최근 발급순: 회원 ERD 최신 링크 → 주문 ERD 링크 → 회원 ERD 옛 링크 → 종료된 링크 → 시작 전 링크
        given(shareRepository.findAllByOrderByCreatedAtDescIdDesc()).willReturn(List.of(
                share(502L, "tokB2", null, null, "2026-09-15T10:00:00Z", 12L),
                share(501L, "tokA", null, null, "2026-09-14T10:00:00Z", 11L),
                share(502L, "tokB1", PAST, FUTURE, "2026-09-13T10:00:00Z", 10L),
                share(503L, "tokC", PAST, PAST, "2026-09-12T10:00:00Z", 9L),
                share(504L, "tokD", FUTURE, null, "2026-09-11T10:00:00Z", 8L)));
        given(modelRepository.findAllById(any())).willReturn(List.of(
                model(501L, "주문 ERD", "2026-09-16T09:00:00Z"),
                model(502L, "회원 ERD", "2026-09-15T09:00:00Z")));

        // when
        List<GalleryShareResponse> gallery = shareService.gallery();

        // then — 주문 ERD(최근 갱신) 먼저, 회원 ERD는 최신 링크 토큰만, 종료·예약 링크 문서는 없다
        assertThat(gallery).hasSize(2);
        assertThat(gallery.get(0).modelName()).isEqualTo("주문 ERD");
        assertThat(gallery.get(0).shareToken()).isEqualTo("tokA");
        assertThat(gallery.get(0).sharedAt()).isEqualTo(Instant.parse("2026-09-14T10:00:00Z"));
        assertThat(gallery.get(0).updatedAt()).isEqualTo(Instant.parse("2026-09-16T09:00:00Z"));
        assertThat(gallery.get(1).modelName()).isEqualTo("회원 ERD");
        assertThat(gallery.get(1).shareToken()).isEqualTo("tokB2");
    }

    @Test
    @DisplayName("갤러리는 활성 링크가 없으면 빈 목록이고 문서를 조회하지 않는다")
    void galleryReturnsEmptyWhenNoActiveShare() {
        given(shareRepository.findAllByOrderByCreatedAtDescIdDesc())
                .willReturn(List.of(share(503L, "tokC", PAST, PAST, "2026-09-12T10:00:00Z", 9L)));

        assertThat(shareService.gallery()).isEmpty();
        then(modelRepository).shouldHaveNoInteractions();
    }

    private static ModelShare share(long modelId, String token, Instant startsAt, Instant endsAt,
                                    String createdAt, long id) {
        ModelShare share = new ModelShare(modelId, token, startsAt, endsAt, 7L);
        ReflectionTestUtils.setField(share, "id", id);
        ReflectionTestUtils.setField(share, "createdAt", Instant.parse(createdAt));
        return share;
    }

    private static Model model(long id, String name, String updatedAt) {
        Model model = new Model(77L, name, "설명", "postgresql", "{}", 7L);
        ReflectionTestUtils.setField(model, "id", id);
        ReflectionTestUtils.setField(model, "updatedAt", Instant.parse(updatedAt));
        return model;
    }
}
