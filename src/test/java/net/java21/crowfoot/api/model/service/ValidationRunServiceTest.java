package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.dto.ValidationRunRequest;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/** 검증 실행 기록 단위 테스트 (08-core/02-model.md §1.13) — 역할 게이트 순서·존재 확인·감사 detail 원문. */
@ExtendWith(MockitoExtension.class)
class ValidationRunServiceTest {

    private static final long USER_ID = 7L;
    private static final long WORKSPACE_ID = 77L;
    private static final long MODEL_ID = 501L;

    @Mock
    private ModelRepository modelRepository;

    @Mock
    private RoleChecker roleChecker;

    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private ValidationRunService validationRunService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> detailCaptor;

    @Test
    @DisplayName("Editor는 건수 원문을 감사 MODEL_VALIDATED로 남긴다 — content는 읽지 않는다")
    void recordsAuditWithCounts() {
        given(modelRepository.findByIdAndWorkspaceId(MODEL_ID, WORKSPACE_ID))
                .willReturn(Optional.of(org.mockito.Mockito.mock(Model.class)));

        validationRunService.record(USER_ID, WORKSPACE_ID, MODEL_ID,
                new ValidationRunRequest(1, 3, 2));

        then(auditRecorder).should().record(eq(USER_ID), eq("MODEL_VALIDATED"), eq("MODEL"), eq("501"),
                detailCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(detailCaptor.getValue())
                .containsEntry("errors", 1)
                .containsEntry("warnings", 3)
                .containsEntry("infos", 2);
    }

    @Test
    @DisplayName("Viewer(열람)는 PERMISSION_DENIED — 역할 검증이 문서 조회보다 먼저다")
    void viewerIsDeniedBeforeLookup() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .when(roleChecker).requireEditor(USER_ID, WORKSPACE_ID);

        assertThatThrownBy(() -> validationRunService.record(USER_ID, WORKSPACE_ID, MODEL_ID,
                new ValidationRunRequest(0, 0, 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        then(modelRepository).shouldHaveNoInteractions();
        then(auditRecorder).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("없는 모델(타 워크스페이스)이면 MODEL_NOT_FOUND — 감사는 남기지 않는다")
    void missingModelIsNotFound() {
        given(modelRepository.findByIdAndWorkspaceId(MODEL_ID, WORKSPACE_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> validationRunService.record(USER_ID, WORKSPACE_ID, MODEL_ID,
                new ValidationRunRequest(0, 0, 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MODEL_NOT_FOUND);
        then(auditRecorder).should(never()).record(any(), any(), any(), any(), any());
    }
}
