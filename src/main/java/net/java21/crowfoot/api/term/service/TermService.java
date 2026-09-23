package net.java21.crowfoot.api.term.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.term.domain.WorkspaceTerm;
import net.java21.crowfoot.api.term.dto.TermResponse;
import net.java21.crowfoot.api.term.dto.UpsertTermRequest;
import net.java21.crowfoot.api.term.repository.WorkspaceTermRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final RoleChecker roleChecker;
    private final AuditRecorder auditRecorder;

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

        WorkspaceTerm entity = termRepository.findByWorkspaceIdAndTerm(workspaceId, term).orElse(null);
        if (entity == null) {
            if (termRepository.countByWorkspaceId(workspaceId) >= MAX_TERMS_PER_WORKSPACE) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "워크스페이스당 용어는 " + MAX_TERMS_PER_WORKSPACE + "개까지 등록할 수 있습니다");
            }
            entity = new WorkspaceTerm(workspaceId, term, label, userId);
        } else {
            entity.setLabel(label);
        }
        WorkspaceTerm saved = termRepository.save(entity);
        auditRecorder.record(userId, "WORKSPACE_TERM_UPSERTED", "WORKSPACE",
                Long.toString(workspaceId), Map.of("term", term, "label", label));
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
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "용어는 공백 없이 하나의 토큰이어야 합니다");
        }
        return term;
    }

    private TermResponse toResponse(WorkspaceTerm term) {
        return new TermResponse(
                Long.toString(term.getId()),
                Long.toString(term.getWorkspaceId()),
                term.getTerm(),
                term.getLabel(),
                term.getUpdatedAt());
    }
}
