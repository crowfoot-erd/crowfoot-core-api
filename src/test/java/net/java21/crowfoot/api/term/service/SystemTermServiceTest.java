package net.java21.crowfoot.api.term.service;

import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.term.domain.SystemTerm;
import net.java21.crowfoot.api.term.dto.SystemTermResponse;
import net.java21.crowfoot.api.term.dto.UpsertSystemTermRequest;
import net.java21.crowfoot.api.term.repository.SystemTermQueryRepository;
import net.java21.crowfoot.api.term.repository.SystemTermRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 시스템 사전 API 단위 테스트 (08-core/01-workspace.md Section 4.5) —
 * upsert(정규화·labels 검증·기존 갱신·상한)·삭제·labels JSON 왕복·감사·관리자 게이트.
 * ObjectMapper는 실물을 쓴다 — labels 맵↔JSONB 문자열 변환이 서비스의 실제 책임이기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class SystemTermServiceTest {

    @Mock
    private SystemTermRepository termRepository;
    @Mock
    private SystemTermQueryRepository queryRepository;
    @Mock
    private DatabaseTypeRepository databaseTypeRepository;
    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditRecorder auditRecorder;

    private SystemTermService systemTermService;

    @BeforeEach
    void setUp() {
        systemTermService = new SystemTermService(termRepository, queryRepository, databaseTypeRepository,
                adminGuard, auditRecorder, new ObjectMapper());
    }

    /** 저장된 것과 같은 형태 — id·타임스탬프는 DB가 채우는 값이라 리플렉션으로 채운다 */
    private SystemTerm saved(long id, String term, String labelsJson, String termTypes) {
        SystemTerm entity = new SystemTerm(term, labelsJson, termTypes, 2L);
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "createdAt", Instant.parse("2026-09-24T00:00:00Z"));
        ReflectionTestUtils.setField(entity, "updatedAt", Instant.parse("2026-09-24T00:00:00Z"));
        return entity;
    }

    /** 순서까지 고정된 labels 입력 — 직렬 결과(JSON 문자열) 단언이 순서에 의존한다 */
    private Map<String, String> linkedLabels(String... pairs) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            labels.put(pairs[i], pairs[i + 1]);
        }
        return labels;
    }

    /** database_types 등록 코드 — types 맵 검증의 원천(활성·비활성 불문 전체 조회) */
    private void registeredCodes() {
        given(databaseTypeRepository.findAllByOrderByCodeAsc()).willReturn(java.util.List.of(
                new DatabaseType("mysql", "MySQL", true),
                new DatabaseType("postgresql", "PostgreSQL", true)));
    }

    private void savingReturnsId() {
        given(termRepository.save(any())).willAnswer((invocation) -> {
            SystemTerm entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 21L);
            return entity;
        });
    }

    @Test
    @DisplayName("사용자 목록 — 역할 검사 없이 페이징 응답으로 내린다(labels·types 맵 왕복)")
    void listReturnsLabelsWithoutRoleCheck() {
        given(queryRepository.count(null, null)).willReturn(2L);
        given(queryRepository.search(null, null, 0L, 20)).willReturn(java.util.List.of(
                saved(21L, "email", "{\"ko\":\"이메일\",\"en\":\"Email\"}", "{\"mysql\":\"VARCHAR(100)\"}"),
                saved(22L, "user", "{\"ko\":\"사용자\"}", null)));

        ListApiResponse<SystemTermResponse> response = systemTermService.list(null, null, null, null);

        assertThat(response.responses()).hasSize(2);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.responses().get(0).term()).isEqualTo("email");
        assertThat(response.responses().get(0).labels()).containsEntry("ko", "이메일").containsEntry("en", "Email");
        assertThat(response.responses().get(0).types()).containsEntry("mysql", "VARCHAR(100)");
        assertThat(response.responses().get(1).labels()).containsExactlyEntriesOf(Map.of("ko", "사용자"));
        assertThat(response.responses().get(1).types()).isNull();
        then(adminGuard).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("사용자 목록 — 파라미터를 정규화해 넘긴다(page 1 클램프·size 100,000 상한·letter 소문자·keyword trim)")
    void listNormalizesPagingAndFilters() {
        given(queryRepository.count("이메일", "e")).willReturn(1L);
        given(queryRepository.search("이메일", "e", 0L, 100_000)).willReturn(java.util.List.of(
                saved(21L, "email", "{\"ko\":\"이메일\"}", null)));

        // size 500은 통과(추론의 전체 로딩), 500,000은 상한 100,000으로 조정된다
        ListApiResponse<SystemTermResponse> response =
                systemTermService.list(0, 500_000, " E ", " 이메일 ");

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(100_000);
        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.responses()).extracting(SystemTermResponse::term).containsExactly("email");

        given(queryRepository.search("이메일", "e", 0L, 500)).willReturn(java.util.List.of(
                saved(21L, "email", "{\"ko\":\"이메일\"}", null)));
        assertThat(systemTermService.list(0, 500, "E", "이메일").size()).isEqualTo(500);
    }

    @Test
    @DisplayName("사용자 목록 — 빈 keyword·무효 letter는 전체 조회로 본다('#'은 그대로 전달)")
    void listTreatsBlankKeywordAndInvalidLetterAsAll() {
        given(queryRepository.count(null, null)).willReturn(0L);
        assertThat(systemTermService.list(1, 20, "ee", "  ").totalCount()).isZero();

        given(queryRepository.count(null, "#")).willReturn(0L);
        assertThat(systemTermService.list(1, 20, "#", null).totalCount()).isZero();
    }

    @Test
    @DisplayName("관리 목록 — AdminGuard를 지나고 감사(ADMIN_SYSTEM_TERMS_LISTED)를 남긴다")
    void adminListRecordsAudit() {
        given(queryRepository.count(null, null)).willReturn(0L);

        systemTermService.adminList(2L, null, null, null, null);

        verify(adminGuard).requireAdmin(2L);
        then(auditRecorder).should().record(2L, "ADMIN_SYSTEM_TERMS_LISTED", "SYSTEM_TERM", "ALL", null);
    }

    @Test
    @DisplayName("upsert 신규 — term·labels·types(DBMS별)을 정규화해 저장하고 맵으로 감사를 남긴다")
    void upsertInsertsNormalizedTerm() {
        registeredCodes();
        given(termRepository.findByTerm("email")).willReturn(Optional.empty());
        savingReturnsId();

        SystemTermResponse response = systemTermService.upsert(2L, new UpsertSystemTermRequest(
                "  Email ", linkedLabels("ko", " 이메일 ", "en", "Email"),
                linkedLabels("mysql", " VARCHAR(100) ", "postgresql", "VARCHAR(100)")));

        assertThat(response.termId()).isEqualTo("21");
        assertThat(response.term()).isEqualTo("email");
        assertThat(response.labels()).containsEntry("ko", "이메일");
        assertThat(response.types()).containsEntry("mysql", "VARCHAR(100)")
                .containsEntry("postgresql", "VARCHAR(100)");

        ArgumentCaptor<SystemTerm> captor = ArgumentCaptor.forClass(SystemTerm.class);
        verify(termRepository).save(captor.capture());
        assertThat(captor.getValue().getLabels()).isEqualTo("{\"ko\":\"이메일\",\"en\":\"Email\"}");
        assertThat(captor.getValue().getTermTypes())
                .isEqualTo("{\"mysql\":\"VARCHAR(100)\",\"postgresql\":\"VARCHAR(100)\"}");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(2L);

        then(auditRecorder).should().record(2L, "SYSTEM_TERM_UPSERTED", "SYSTEM_TERM", "21",
                Map.of("term", "email", "labels", Map.of("ko", "이메일", "en", "Email"),
                        "types", Map.of("mysql", "VARCHAR(100)", "postgresql", "VARCHAR(100)")));
    }

    @Test
    @DisplayName("upsert 기존 — labels·types만 갱신한다(상한 검사도 건너뛴다), 값이 전부 빈 types는 null")
    void upsertUpdatesExistingLabels() {
        registeredCodes();
        SystemTerm existing = saved(21L, "email", "{\"ko\":\"전자우편\"}", "{\"mysql\":\"VARCHAR(50)\"}");
        given(termRepository.findByTerm("email")).willReturn(Optional.of(existing));
        given(termRepository.save(existing)).willReturn(existing);

        SystemTermResponse response = systemTermService.upsert(5L, new UpsertSystemTermRequest(
                "EMAIL", Map.of("ko", "이메일"), Map.of("mysql", "  ")));

        assertThat(existing.getLabels()).isEqualTo("{\"ko\":\"이메일\"}");
        assertThat(existing.getTermTypes()).isNull();
        assertThat(response.termId()).isEqualTo("21");
        assertThat(response.types()).isNull();
        verify(termRepository, never()).count();
        // 감사 detail에는 null types를 싣지 않는다
        then(auditRecorder).should().record(5L, "SYSTEM_TERM_UPSERTED", "SYSTEM_TERM", "21",
                Map.of("term", "email", "labels", Map.of("ko", "이메일")));
    }

    @Test
    @DisplayName("upsert — types 키가 database_types에 없으면 400 INVALID_REQUEST다")
    void upsertRejectsUnknownDatabaseCode() {
        registeredCodes();

        assertThatThrownBy(() -> systemTermService.upsert(2L,
                new UpsertSystemTermRequest("email", Map.of("ko", "이메일"),
                        Map.of("oracle", "NUMBER(19)"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(termRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert — types 값이 100자를 넘으면 400 INVALID_REQUEST다")
    void upsertRejectsTooLongTypeValue() {
        registeredCodes();

        assertThatThrownBy(() -> systemTermService.upsert(2L,
                new UpsertSystemTermRequest("email", Map.of("ko", "이메일"),
                        Map.of("mysql", "V".repeat(101)))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(termRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert — term에 공백이 있으면 400 INVALID_REQUEST다")
    void upsertRejectsWhitespaceInTerm() {
        assertThatThrownBy(() -> systemTermService.upsert(2L,
                new UpsertSystemTermRequest("e mail", Map.of("ko", "이메일"), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(termRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert — labels가 비었거나 8개를 넘으면 400 INVALID_REQUEST다")
    void upsertRejectsEmptyOrTooManyLabels() {
        assertThatThrownBy(() -> systemTermService.upsert(2L,
                new UpsertSystemTermRequest("email", Map.of(), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        Map<String, String> nine = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            nine.put("l" + i, "라벨" + i);
        }
        assertThatThrownBy(() -> systemTermService.upsert(2L,
                new UpsertSystemTermRequest("email", nine, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(termRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert — 언어나 라벨이 trim 후 비거나 100자를 넘으면 400 INVALID_REQUEST다")
    void upsertRejectsBlankOrTooLongLabelParts() {
        // 빈 언어 키
        assertThatThrownBy(() -> systemTermService.upsert(2L,
                new UpsertSystemTermRequest("email", Map.of("  ", "이메일"), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        // 빈 라벨 값
        assertThatThrownBy(() -> systemTermService.upsert(2L,
                new UpsertSystemTermRequest("email", Map.of("ko", "  "), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        // 100자 초과 라벨
        assertThatThrownBy(() -> systemTermService.upsert(2L,
                new UpsertSystemTermRequest("email", Map.of("ko", "라".repeat(101)), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(termRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert — 전체 건수 상한이 없다(v1.15 — 대량 표준 사전): 기존 5,000건을 넘어도 신규 등록된다")
    void upsertHasNoTotalCap() {
        given(termRepository.findByTerm("order")).willReturn(Optional.empty());
        savingReturnsId();

        SystemTermResponse response = systemTermService.upsert(2L,
                new UpsertSystemTermRequest("order", Map.of("ko", "주문"), null));

        assertThat(response.term()).isEqualTo("order");
        verify(termRepository).save(any());
    }

    @Test
    @DisplayName("권한 — 관리 액션은 AdminGuard를 먼저 지난다(비관리자면 저장 전에 403)")
    void adminGateComesFirst() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .when(adminGuard).requireAdmin(5L);

        assertThatThrownBy(() -> systemTermService.upsert(5L,
                new UpsertSystemTermRequest("email", Map.of("ko", "이메일"), null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> systemTermService.delete(5L, 21L))
                .isInstanceOf(BusinessException.class);
        verify(termRepository, never()).save(any());
        verify(termRepository, never()).delete(any());
    }

    @Test
    @DisplayName("삭제 — 없는 id면 404 TERM_NOT_FOUND, 정상 삭제는 감사를 남긴다")
    void deleteAuditsAndHidesMissing() {
        given(termRepository.findById(99L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> systemTermService.delete(2L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TERM_NOT_FOUND);

        SystemTerm existing = saved(21L, "email", "{\"ko\":\"이메일\"}", null);
        given(termRepository.findById(21L)).willReturn(Optional.of(existing));

        systemTermService.delete(2L, 21L);

        verify(termRepository).delete(existing);
        then(auditRecorder).should().record(2L, "SYSTEM_TERM_DELETED", "SYSTEM_TERM", "21",
                Map.of("term", "email"));
    }
}
