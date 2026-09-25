package net.java21.crowfoot.api.term.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.term.domain.WorkspaceTerm;
import net.java21.crowfoot.api.term.dto.TermResponse;
import net.java21.crowfoot.api.term.dto.UpsertTermRequest;
import net.java21.crowfoot.api.term.repository.WorkspaceTermRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 워크스페이스 용어 사전 API (08-core/01-workspace.md Section 4) — 목록·upsert·삭제.
 * 에디터의 논리명 자동 추론이 내장 사전에 우선해 참조하는 커스텀 사전이다.
 *
 * <p>권한: 목록은 멤버 전체(추론 미리보기를 볼 수 있어야 한다), 등록·수정·삭제는 Editor 이상.
 * 등록은 (workspace_id, term) 자연키 upsert라 항상 200 — 수정과 등록을 나누지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TermService {

    /** 워크스페이스당 용어 상한 — 사전은 사람이 읽는 자원이라 무한으로 두지 않는다 */
    static final int MAX_TERMS_PER_WORKSPACE = 1_000;

    private final WorkspaceTermRepository termRepository;
    private final DatabaseTypeRepository databaseTypeRepository;
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    /** 목록(멤버 전체 — 4.1) — term 오름차순 */
    @Transactional(readOnly = true)
    public List<TermResponse> list(long userId, long workspaceId) {
        roleChecker.requireMember(userId, workspaceId);
        return termRepository.findByWorkspaceIdOrderByTermAsc(workspaceId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** 등록·수정 upsert(Editor 이상 — 4.2) — term 자연키로 한 행에 정착, 신규일 때만 상한 검사 */
    @Transactional
    public TermResponse upsert(long userId, long workspaceId, UpsertTermRequest request) {
        roleChecker.requireEditor(userId, workspaceId);
        String term = normalizeTerm(request.term());
        String label = request.label().trim();
        Map<String, String> types = normalizeTypes(request.types());
        String typesJson = types == null ? null : writeMap(types);

        WorkspaceTerm entity = termRepository.findByWorkspaceIdAndTerm(workspaceId, term).orElse(null);
        if (entity == null) {
            if (termRepository.countByWorkspaceId(workspaceId) >= MAX_TERMS_PER_WORKSPACE) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "워크스페이스당 용어는 " + MAX_TERMS_PER_WORKSPACE + "개까지 등록할 수 있습니다");
            }
            entity = new WorkspaceTerm(workspaceId, term, label, typesJson, userId);
        } else {
            entity.setLabel(label);
            entity.setTermTypes(typesJson);
        }
        WorkspaceTerm saved = termRepository.save(entity);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("term", term);
        detail.put("label", label);
        if (types != null) {
            detail.put("types", types);
        }
        auditRecorder.record(userId, "WORKSPACE_TERM_UPSERTED", "WORKSPACE",
                Long.toString(workspaceId), detail);
        return toResponse(saved);
    }

    /** 삭제(Editor 이상 — 4.3) — 소속 워크스페이스 검증(다른 워크스페이스 id 삭제 차단) */
    @Transactional
    public void delete(long userId, long workspaceId, long termId) {
        roleChecker.requireEditor(userId, workspaceId);
        WorkspaceTerm term = termRepository.findById(termId)
                .filter((t) -> Objects.equals(t.getWorkspaceId(), workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.TERM_NOT_FOUND));
        termRepository.delete(term);
        auditRecorder.record(userId, "WORKSPACE_TERM_DELETED", "WORKSPACE",
                Long.toString(workspaceId), Map.of("term", term.getTerm(), "label", term.getLabel()));
    }

    /** 물리명 토큰 정규화 — trim + 소문자. 토큰에는 공백이 없다(추론이 '_', camelCase로만 분해한다) */
    private String normalizeTerm(String raw) {
        String term = raw == null ? "" : raw.trim().toLowerCase();
        if (term.isEmpty() || term.chars().anyMatch(Character::isWhitespace)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.term.token");
        }
        return term;
    }

    /** DBMS 종류별 타입 맵 검증·정규화 — SystemTermService와 같은 규칙(키는 database_types 등록 코드,
     *  값 trim 후 빈 값은 버린다, 전부 비면 null) */
    private Map<String, String> normalizeTypes(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Set<String> codes = databaseTypeRepository.findAllByOrderByCodeAsc().stream()
                .map(DatabaseType::getCode)
                .collect(Collectors.toSet());
        Map<String, String> types = new LinkedHashMap<>();
        raw.forEach((code, value) -> {
            String key = code == null ? "" : code.trim();
            String type = value == null ? "" : value.trim();
            if (type.isEmpty()) {
                return;
            }
            if (key.isEmpty() || !codes.contains(key)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "지원하지 않는 데이터베이스 종류입니다: " + key);
            }
            if (type.length() > 100) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.term.datatype.length");
            }
            types.put(key, type);
        });
        return types.isEmpty() ? null : types;
    }

    private String writeMap(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.term.value.unsavable");
        }
    }

    private Map<String, String> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("용어 사전 맵 파싱 실패: " + json, ex);
        }
    }

    private TermResponse toResponse(WorkspaceTerm term) {
        return new TermResponse(
                Long.toString(term.getId()),
                Long.toString(term.getWorkspaceId()),
                term.getTerm(),
                term.getLabel(),
                term.getTermTypes() == null ? null : readMap(term.getTermTypes()),
                term.getUpdatedAt());
    }
}
