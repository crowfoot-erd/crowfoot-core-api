package net.java21.crowfoot.api.model.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 버전 보존 정책(1.11.6) 단위 테스트 — 최근 KEEP_COUNT개 경계 삭제·조건부 감사를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ModelVersionPrunerTest {

    @Mock
    private ModelVersionRepository modelVersionRepository;
    @Mock
    private AuditRecorder auditRecorder;

    private ModelVersionPruner pruner;

    @BeforeEach
    void setUp() {
        pruner = new ModelVersionPruner(modelVersionRepository, auditRecorder);
    }

    @Test
    @DisplayName("삭제 경계는 newVersion-KEEP_COUNT다 — 150 저장이면 v50 이하 삭제(경계 포함)·v51부터 보존")
    void pruneDeletesAtCutoffBoundary() {
        pruner.prune(501L, 150L, 7L);

        verify(modelVersionRepository).deleteByModelIdAndVersionLessThanEqual(501L, 50L);
    }

    @Test
    @DisplayName("삭제가 일어난 경우에만 감사 MODEL_VERSION_PRUNED를 남긴다 — pruned·keptFrom 상세")
    void pruneAuditsOnlyWhenDeleted() {
        given(modelVersionRepository.deleteByModelIdAndVersionLessThanEqual(501L, 50L)).willReturn(3L);

        pruner.prune(501L, 150L, 7L);

        verify(auditRecorder).record(7L, "MODEL_VERSION_PRUNED", "MODEL", "501",
                java.util.Map.of("pruned", 3L, "keptFrom", 51L));
    }

    @Test
    @DisplayName("삭제 0건(초기 100 저장)은 감사를 남기지 않는다 — 0건 감사 잡음 방지")
    void pruneSkipsAuditWhenNothingDeleted() {
        given(modelVersionRepository.deleteByModelIdAndVersionLessThanEqual(anyLong(), anyLong()))
                .willReturn(0L);

        pruner.prune(501L, 99L, 7L);

        verify(auditRecorder, never()).record(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("KEEP_COUNT는 100이다 — 보존 정책 상수 고정")
    void keepCountIsHundred() {
        assertThat(ModelVersionPruner.KEEP_COUNT).isEqualTo(100);
    }
}
