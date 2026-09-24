package net.java21.crowfoot.api.term.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.term.domain.WorkspaceTerm;
import net.java21.crowfoot.api.term.dto.TermResponse;
import net.java21.crowfoot.api.term.dto.UpsertTermRequest;
import net.java21.crowfoot.api.term.repository.WorkspaceTermRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 용어 사전 API 단위 테스트 (08-core/01-workspace.md Section 4) —
 * upsert(정규화·기존 갱신·상한·type)·삭제(소속 검증)·감사·권한 게이트.
 */
@ExtendWith(MockitoExtension.class)
class TermServiceTest {

    @Mock
    private WorkspaceTermRepository termRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;

    private TermService termService;

    @BeforeEach
    void setUp() {
        termService = new TermService(termRepository, roleChecker, auditRecorder);
    }

    /** 저장된 것과 같은 형태 — id·타임스탬프는 DB가 채우는 값이라 리플렉션으로
     *  채운다(생성자는 저장 전 값만 받는다) */
    private WorkspaceTerm saved(long id, long workspaceId, String term, String label, String termType) {
        WorkspaceTerm entity = new WorkspaceTerm(workspaceId, term, label, termType, 2L);
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "createdAt", Instant.parse("2026-09-23T00:00:00Z"));
        ReflectionTestUtils.setField(entity, "updatedAt", Instant.parse("2026-09-23T00:00:00Z"));
        return entity;
    }

    @Test
    @DisplayName("upsert 신규 — term·type을 정규화해 저장하고 감사를 남긴다")
    void upsertInsertsNormalizedTerm() {
        given(termRepository.findByWorkspaceIdAndTerm(7L, "user")).willReturn(Optional.empty());
        given(termRepository.countByWorkspaceId(7L)).willReturn(0L);
        given(termRepository.save(any())).willAnswer((invocation) -> {
            WorkspaceTerm entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 11L);
            return entity;
        });

        TermResponse response = termService.upsert(2L, 7L,
                new UpsertTermRequest("  User ", "사용자", " VARCHAR(100) "));

        assertThat(response.termId()).isEqualTo("11");
        assertThat(response.term()).isEqualTo("user");
        assertThat(response.label()).isEqualTo("사용자");
        assertThat(response.type()).isEqualTo("VARCHAR(100)");

        ArgumentCaptor<WorkspaceTerm> captor = ArgumentCaptor.forClass(WorkspaceTerm.class);
        verify(termRepository).save(captor.capture());
        assertThat(captor.getValue().getWorkspaceId()).isEqualTo(7L);
        assertThat(captor.getValue().getTermType()).isEqualTo("VARCHAR(100)");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(2L);

        then(auditRecorder).should().record(2L, "WORKSPACE_TERM_UPSERTED", "WORKSPACE", "7",
                Map.of("term", "user", "label", "사용자", "type", "VARCHAR(100)"));
    }

    @Test
    @DisplayName("upsert 기존 — 라벨·type을 갱신한다(상한 검사도 건너뛴다), type 빈 문자열은 null")
    void upsertUpdatesExistingLabel() {
        WorkspaceTerm existing = saved(11L, 7L, "user", "유저", "VARCHAR(50)");
        given(termRepository.findByWorkspaceIdAndTerm(7L, "user")).willReturn(Optional.of(existing));
        given(termRepository.save(existing)).willReturn(existing);

        TermResponse response = termService.upsert(5L, 7L, new UpsertTermRequest("USER", "사용자", "  "));

        assertThat(existing.getLabel()).isEqualTo("사용자");
        assertThat(existing.getTermType()).isNull();
        assertThat(response.termId()).isEqualTo("11");
        assertThat(response.type()).isNull();
        verify(termRepository, never()).countByWorkspaceId(anyLong());
        // 감사 detail에는 null type을 싣지 않는다
        then(auditRecorder).should().record(5L, "WORKSPACE_TERM_UPSERTED", "WORKSPACE", "7",
                Map.of("term", "user", "label", "사용자"));
    }

    @Test
    @DisplayName("upsert — type 생략(null)도 그대로 저장된다(선택 값)")
    void upsertAllowsMissingType() {
        given(termRepository.findByWorkspaceIdAndTerm(7L, "user")).willReturn(Optional.empty());
        given(termRepository.countByWorkspaceId(7L)).willReturn(0L);
        given(termRepository.save(any())).willAnswer((invocation) -> {
            WorkspaceTerm entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 11L);
            return entity;
        });

        TermResponse response = termService.upsert(2L, 7L, new UpsertTermRequest("user", "사용자", null));

        assertThat(response.type()).isNull();
    }

    @Test
    @DisplayName("upsert — term에 공백이 있으면 400 INVALID_REQUEST다")
    void upsertRejectsWhitespaceInTerm() {
        assertThatThrownBy(() -> termService.upsert(2L, 7L, new UpsertTermRequest("us er", "사용자", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(termRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert — 워크스페이스당 1,000개 상한(신규 등록 시에만)")
    void upsertEnforcesCap() {
        given(termRepository.findByWorkspaceIdAndTerm(7L, "order")).willReturn(Optional.empty());
        given(termRepository.countByWorkspaceId(7L)).willReturn(1_000L);

        assertThatThrownBy(() -> termService.upsert(2L, 7L, new UpsertTermRequest("order", "주문", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(termRepository, never()).save(any());
    }

    @Test
    @DisplayName("권한 — 목록은 멤버 게이트(requireMember), upsert·삭제는 Editor 게이트를 지난다")
    void roleGates() {
        given(termRepository.findByWorkspaceIdOrderByTermAsc(7L)).willReturn(java.util.List.of());

        termService.list(2L, 7L);
        verify(roleChecker).requireMember(2L, 7L);

        given(termRepository.findByWorkspaceIdAndTerm(7L, "user")).willReturn(Optional.empty());
        given(termRepository.countByWorkspaceId(7L)).willReturn(0L);
        given(termRepository.save(any())).willAnswer((invocation) -> {
            WorkspaceTerm entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 11L);
            return entity;
        });
        termService.upsert(2L, 7L, new UpsertTermRequest("user", "사용자", null));
        verify(roleChecker).requireEditor(2L, 7L);
    }

    @Test
    @DisplayName("삭제 — 소속 워크스페이스가 아니면 404 TERM_NOT_FOUND")
    void deleteValidatesOwnership() {
        given(termRepository.findById(11L)).willReturn(Optional.of(saved(11L, 8L, "user", "사용자", null)));

        assertThatThrownBy(() -> termService.delete(2L, 7L, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TERM_NOT_FOUND);
        verify(termRepository, never()).delete(any());
    }

    @Test
    @DisplayName("삭제 — 삭제 후 감사(WORKSPACE_TERM_DELETED)를 남긴다")
    void deleteRecordsAudit() {
        WorkspaceTerm existing = saved(11L, 7L, "user", "사용자", null);
        given(termRepository.findById(11L)).willReturn(Optional.of(existing));

        termService.delete(2L, 7L, 11L);

        verify(termRepository).delete(existing);
        then(auditRecorder).should().record(2L, "WORKSPACE_TERM_DELETED", "WORKSPACE", "7",
                Map.of("term", "user", "label", "사용자"));
    }
}
