package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 버전 기록 보존 정책(1.11.6) — 문서당 최근 {@link #KEEP_COUNT}개만 보존하고
 * 초과분은 스냅샷을 남기는 쓰기(저장·생성·리버스·복원)와 같은 트랜잭션에서 바로 지운다.
 * 별도 배치가 아니라 "그 쓰기가 유발한 정리"로 감사 액터도 쓰기 사용자를 따른다.
 * 삭제가 일어난 경우에만 감사를 남긴다 — 초기 100 저장까지 매번 0건 감사가 쌓이는 잡음 방지.
 */
@Component
@RequiredArgsConstructor
public class ModelVersionPruner {

    /** 문서당 보존 버전 수 — 100번째 저장부터 가장 오래된 스냅샷이 밀려난다 */
    static final int KEEP_COUNT = 100;

    private final ModelVersionRepository modelVersionRepository;
    private final AuditRecorder auditRecorder;

    /**
     * newVersion 기준 최근 {@link #KEEP_COUNT}개를 남기고 나머지를 삭제한다.
     * 버전은 단조 증가하므로 경계 하나로 정확한다(newVersion=150 → v50 이하 삭제·v51~v150 보존).
     * 호출부의 쓰기 트랜잭션 안에서 실행된다(파생 delete는 트랜잭션이 필요하다).
     */
    public void prune(Long modelId, long newVersion, Long actorUserId) {
        long cutoff = newVersion - KEEP_COUNT;
        long deleted = modelVersionRepository.deleteByModelIdAndVersionLessThanEqual(modelId, cutoff);
        if (deleted > 0) {
            auditRecorder.record(actorUserId, "MODEL_VERSION_PRUNED", "MODEL",
                    Long.toString(modelId), Map.of("pruned", deleted, "keptFrom", cutoff + 1));
        }
    }
}
