package net.java21.crowfoot.api.account.service;

import tools.jackson.databind.ObjectMapper;
import net.java21.crowfoot.api.account.dto.AuditLogResponse;
import net.java21.crowfoot.api.account.repository.AuditLogQueryRepository;
import net.java21.crowfoot.api.account.repository.AuditLogQueryRepository.AuditLogRow;
import net.java21.crowfoot.common.ListApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 관리자 감사 로그 조회 (08-core/05-account.md Section 2.7) — detail 파싱·필터 위임·감사의 감사 */
@ExtendWith(MockitoExtension.class)
class AdminAuditServiceTest {

    private static final Instant LOGGED_AT = Instant.parse("2026-09-12T01:03:00Z");

    @Mock
    private AdminGuard adminGuard;
    @Mock
    private AuditLogQueryRepository auditLogQueryRepository;
    @Mock
    private AuditRecorder auditRecorder;

    private AdminAuditService adminAuditService;

    @BeforeEach
    void setUp() {
        // ObjectMapper는 실물 — 파싱 동작 자체를 검증한다
        adminAuditService = new AdminAuditService(adminGuard, auditLogQueryRepository, auditRecorder,
                new ObjectMapper());
    }

    @Test
    @DisplayName("목록은 detail JSON을 객체로 파싱해 응답하고 조회 자체를 감사한다(AUDIT/ALL)")
    void logsParsesDetailAndAuditsItself() {
        // given
        AuditLogRow row = new AuditLogRow(118L, LOGGED_AT, 2L, "marco", "marco@x.com",
                "ROLE_UPDATED", "ROLE", "2", "{\"roleName\":\"ADMIN\"}", "1.2.3.4");
        given(auditLogQueryRepository.countAuditLogs("marco", "ROLE_UPDATED")).willReturn(148L);
        given(auditLogQueryRepository.searchAuditLogs("marco", "ROLE_UPDATED", 0L, 20))
                .willReturn(List.of(row));

        // when
        ListApiResponse<AuditLogResponse> result = adminAuditService.logs(2L, " marco ", "ROLE_UPDATED", 1, 20);

        // then — keyword는 trim해 위임
        assertThat(result.totalCount()).isEqualTo(148);
        assertThat(result.responses()).hasSize(1);
        AuditLogResponse response = result.responses().get(0);
        assertThat(response.id()).isEqualTo("118");
        assertThat(response.actorName()).isEqualTo("marco");
        assertThat(response.detail()).containsEntry("roleName", "ADMIN");
        verify(auditRecorder).record(2L, "ADMIN_AUDIT_LOGS_LISTED", "AUDIT", "ALL",
                Map.of("keyword", "marco", "action", "ROLE_UPDATED"));
    }

    @Test
    @DisplayName("빈 keyword·action은 null로 전체 조회하고 감사 detail도 생략한다")
    void blankFiltersMeanAll() {
        // given
        AuditLogRow systemRow = new AuditLogRow(117L, LOGGED_AT, null, null, null,
                "TOKEN_REFRESHED", "TOKEN", "SYSTEM", null, null);
        given(auditLogQueryRepository.countAuditLogs(null, null)).willReturn(1L);
        given(auditLogQueryRepository.searchAuditLogs(null, null, 0L, 20)).willReturn(List.of(systemRow));

        // when
        ListApiResponse<AuditLogResponse> result = adminAuditService.logs(2L, "  ", "", null, null);

        // then — 미기록 detail은 null
        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.responses().get(0).detail()).isNull();
        assertThat(result.responses().get(0).actorUserId()).isNull();
        verify(auditRecorder).record(2L, "ADMIN_AUDIT_LOGS_LISTED", "AUDIT", "ALL", null);
    }

    @Test
    @DisplayName("깨진 detail JSON은 null로 응답한다 — 조회는 실패하지 않는다")
    void brokenDetailBecomesNull() {
        // given
        AuditLogRow row = new AuditLogRow(116L, LOGGED_AT, 2L, "marco", "marco@x.com",
                "USER_LOGGED_IN", "USER", "3", "{broken", "1.2.3.4");
        given(auditLogQueryRepository.countAuditLogs(null, null)).willReturn(1L);
        given(auditLogQueryRepository.searchAuditLogs(null, null, 0L, 20)).willReturn(List.of(row));

        // when
        ListApiResponse<AuditLogResponse> result = adminAuditService.logs(2L, null, null, null, null);

        // then
        assertThat(result.responses().get(0).detail()).isNull();
        assertThat(result.responses().get(0).action()).isEqualTo("USER_LOGGED_IN");
    }

    @Test
    @DisplayName("total이 0이면 검색 쿼리 없이 빈 페이지를 응답한다")
    void emptyResultSkipsSearch() {
        // given
        given(auditLogQueryRepository.countAuditLogs("없는사람", null)).willReturn(0L);

        // when
        ListApiResponse<AuditLogResponse> result = adminAuditService.logs(2L, "없는사람", null, 1, 20);

        // then
        assertThat(result.totalCount()).isZero();
        assertThat(result.responses()).isEmpty();
        verify(auditLogQueryRepository, never()).searchAuditLogs(any(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("size·page는 경계로 정규화한다 — 기본 20·상한 100·page 최소 1")
    void normalizesPagingParams() {
        // given
        given(auditLogQueryRepository.countAuditLogs(null, null)).willReturn(1L);
        given(auditLogQueryRepository.searchAuditLogs(null, null, 0L, 100)).willReturn(List.of(
                new AuditLogRow(1L, LOGGED_AT, null, null, null, "TOKEN_REFRESHED", "TOKEN", "SYSTEM", null, null)));

        // when: size 500 → 100(상한), page 0 → 1
        ListApiResponse<AuditLogResponse> result = adminAuditService.logs(2L, null, null, 0, 500);

        // then
        assertThat(result.size()).isEqualTo(100);
        assertThat(result.page()).isEqualTo(1);
        verify(auditLogQueryRepository).searchAuditLogs(null, null, 0L, 100);
    }
}
