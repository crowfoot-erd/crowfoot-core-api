package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.dto.ValidationRunRequest;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 검증 실행 기록 (08-core/02-model.md §1.13, 05-editor/05-validation.md §5) — 에디터 린터가
 * 검증을 돌렸다는 사실과 등급별 건수를 감사로만 남긴다. 검증 규칙은 에디터 소유라 서버는
 * content를 재해석하지 않는다(스키마 무변경). 남용 방어는 Gateway 전역 IP 레이트리밋이 담당.
 */
@Service
@RequiredArgsConstructor
public class ValidationRunService {

    private final ModelRepository modelRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;

    /** 검증 실행 기록 — Editor 이상, 문서 존재만 확인(본문 읽지 않음) 후 감사 MODEL_VALIDATED */
    public void record(long userId, long workspaceId, long modelId, ValidationRunRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        if (modelRepository.findByIdAndWorkspaceId(modelId, workspaceId).isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND);
        }
        // 세 건수는 의도된 예외를 뺀 값이다. 예외 건수는 v1.34 화면부터 온다(05-editor/05-validation.md Section 4.4)
        auditRecorder.record(userId, "MODEL_VALIDATED", "MODEL", Long.toString(modelId), Map.of(
                "errors", request.errorCount(),
                "warnings", request.warningCount(),
                "infos", request.infoCount(),
                "exceptions", request.exceptionCount() == null ? 0 : request.exceptionCount()));
    }
}
