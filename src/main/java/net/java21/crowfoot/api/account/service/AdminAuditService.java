package net.java21.crowfoot.api.account.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.dto.AdminPageParams;
import net.java21.crowfoot.api.account.dto.AuditLogResponse;
import net.java21.crowfoot.api.account.repository.AuditLogQueryRepository;
import net.java21.crowfoot.api.account.repository.AuditLogQueryRepository.AuditLogRow;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 — 감사 로그 조회 (08-core/05-account.md Section 2.7).
 * 읽기 전용 — 폐기·수정 액션은 없고, 조회 자체가 감사로 남는다(ADMIN_AUDIT_LOGS_LISTED).
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private static final TypeReference<Map<String, Object>> DETAIL_TYPE = new TypeReference<>() {
    };

    private final AdminGuard adminGuard;
    private final AuditLogQueryRepository auditLogQueryRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    /** 감사 로그 목록 — keyword(주체 이름·이메일 부분 일치)·action(정확 일치)·페이징, id desc 고정 */
    @Transactional(readOnly = true)
    public ListApiResponse<AuditLogResponse> logs(long adminId, String keyword, String action, Integer page, Integer size) {
        adminGuard.requireAdmin(adminId);
        String trimmedKeyword = trimToNull(keyword);
        String trimmedAction = trimToNull(action);
        AdminPageParams params = AdminPageParams.of(page, size);

        long total = auditLogQueryRepository.countAuditLogs(trimmedKeyword, trimmedAction);
        List<AuditLogRow> rows = total == 0 ? List.of()
                : auditLogQueryRepository.searchAuditLogs(trimmedKeyword, trimmedAction, params.offset(), params.size());

        auditRecorder.record(adminId, "ADMIN_AUDIT_LOGS_LISTED", "AUDIT", "ALL", auditDetail(trimmedKeyword, trimmedAction));
        return ListApiResponse.paged(rows.stream().map(this::toResponse).toList(),
                params.page(), params.size(), total);
    }

    /** detail(JSON 문자열)은 파싱된 객체로 응답한다 — 미기록·깨진 값은 null */
    private AuditLogResponse toResponse(AuditLogRow row) {
        return new AuditLogResponse(row.id(), row.createdAt(), row.actorUserId(), row.actorName(),
                row.actorEmail(), row.action(), row.targetType(), row.targetId(), parseDetail(row.detail()), row.ip());
    }

    private Map<String, Object> parseDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(detail, DETAIL_TYPE);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Map<String, Object> auditDetail(String keyword, String action) {
        if (keyword == null && action == null) {
            return null;
        }
        Map<String, Object> detail = new HashMap<>();
        if (keyword != null) {
            detail.put("keyword", keyword);
        }
        if (action != null) {
            detail.put("action", action);
        }
        return detail;
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
