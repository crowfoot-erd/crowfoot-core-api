package net.java21.crowfoot.api.model.sync;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.model.repository.ModelVersionRepository;
import net.java21.crowfoot.api.model.service.ModelVersionPruner;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동기화 결과 저장 — 문서 편집 API(08-core/17-model-edit.md)와 같은 저장 경로다 (08-core/02-model.md Section 1.16.2).
 * 조건부 UPDATE(낙관적 잠금)로 버전을 1 올리고, 버전 스냅샷을 남기고, 보존 개수를 넘은 스냅샷을 정리한다.
 * 외부 DB 접속(introspection)은 이 트랜잭션 밖에서 끝낸다 — DB 커넥션을 오래 물지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DocumentSyncWriter {

    private final ModelRepository modelRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final ModelVersionPruner modelVersionPruner;

    /** 저장한 새 버전을 돌려준다. 읽은 뒤 다른 저장이 끼어들었으면 409 VERSION_CONFLICT */
    @Transactional
    public long save(long workspaceId, long modelId, long baseVersion, String content, String changeSummary, long userId) {
        Instant now = Instant.now();
        int updated = modelRepository.updateContentIfVersionMatches(modelId, workspaceId, baseVersion, content, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        long version = baseVersion + 1;
        modelVersionRepository.save(new ModelVersion(modelId, version, content, changeSummary, null, userId, now));
        modelVersionPruner.prune(modelId, version, userId);
        return version;
    }
}
