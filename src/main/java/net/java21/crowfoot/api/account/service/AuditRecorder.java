package net.java21.crowfoot.api.account.service;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 감사 기록 (06-erd/00-domain.md Section 3.7) — INSERT-only, 기록 실패가 본류를 실패시키지 않는다(best-effort).
 *
 * <p>쓰기는 {@link AuditLogWriter}의 별도 트랜잭션(REQUIRES_NEW)으로 수행한다 —
 * 조회(readOnly) 트랜잭션 컨텍스트에서도 INSERT가 가능해야 하고(관리자 조회 액션 감사),
 * 폐기 등 본류 롤백과 무관하게 남는다(SessionRevoker의 감사 별도 커밋과 같은 규칙).
 * catch는 트랜잭션 경계 밖(여기)에 둔다 — 경계 안에서 삼키면 rollback-only 커밋이
 * UnexpectedRollbackException으로 새어나가 본류를 죽인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditRecorder {

    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;

    public void record(Long actorUserId, String action, String targetType, String targetId, Map<String, Object> detail) {
        try {
            String detailJson = detail == null ? null : objectMapper.writeValueAsString(detail);
            auditLogWriter.write(actorUserId, action, targetType, targetId, detailJson);
        } catch (Exception ex) {
            log.warn("감사 기록 실패(action={}, target={}/{}) — 본류에는 영향 없음: {}",
                    action, targetType, targetId, ex.getMessage());
        }
    }
}
