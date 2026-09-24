package net.java21.crowfoot.api.term.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.service.AdminGuard;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.api.term.domain.SystemTerm;
import net.java21.crowfoot.api.term.dto.SystemTermResponse;
import net.java21.crowfoot.api.term.dto.UpsertSystemTermRequest;
import net.java21.crowfoot.api.term.repository.SystemTermRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 시스템 사전 API (08-core/01-workspace.md Section 4.5) — 전역 용어 사전의 조회·관리.
 *
 * <p>권한: 목록은 인증된 사용자 전체(전 워크스페이스가 공유하는 추론 바닥 사전 —
 * /core/teams와 같은 전역 읽기), 등록·수정·삭제는 관리자만(AdminGuard).
 * 등록은 term 전역 자연키 upsert라 항상 200이다.
 * labels는 언어→라벨 맵을 그대로 저장·반환하고, 어떤 언어를 보여줄지는 클라이언트가 정한다.
 */
@Service
@RequiredArgsConstructor
public class SystemTermService {

    /** 시스템 사전 전체 상한 — 관리자가 등록하는 자원이지만 방치 상한을 둔다 */
    static final int MAX_SYSTEM_TERMS = 5_000;

    /** 언어→라벨 맵 엔트리 상한 — 관리 폼 로케일 후보(ko/en/ja/zh)보다 여유를 둔다 */
    private static final int MAX_LABEL_ENTRIES = 8;

    private final SystemTermRepository termRepository;
    private final DatabaseTypeRepository databaseTypeRepository;
    private final AdminGuard adminGuard;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    /** 사용자 목록(인증 전체) — term 오름차순. 시스템 사전은 읽기만 가능한 전역 사전이다 */
    @Transactional(readOnly = true)
    public List<SystemTermResponse> list() {
        return termRepository.findAllByOrderByTermAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** 관리 목록(관리자) — 목록 자체가 관리 액션이라 감사를 남긴다 */
    @Transactional(readOnly = true)
    public List<SystemTermResponse> adminList(long adminId) {
        adminGuard.requireAdmin(adminId);
        List<SystemTermResponse> responses = termRepository.findAllByOrderByTermAsc()
                .stream()
                .map(this::toResponse)
                .toList();
        auditRecorder.record(adminId, "ADMIN_SYSTEM_TERMS_LISTED", "SYSTEM_TERM", "ALL", null);
        return responses;
    }

    /** 등록·수정 upsert(관리자) — term 자연키로 한 행에 정착, 신규일 때만 상한 검사 */
    @Transactional
    public SystemTermResponse upsert(long adminId, UpsertSystemTermRequest request) {
        adminGuard.requireAdmin(adminId);
        String term = normalizeTerm(request.term());
        Map<String, String> labels = normalizeLabels(request.labels());
        Map<String, String> types = normalizeTypes(request.types());
        String labelsJson = writeMap(labels);
        String typesJson = types == null ? null : writeMap(types);

        SystemTerm entity = termRepository.findByTerm(term).orElse(null);
        if (entity == null) {
            if (termRepository.count() >= MAX_SYSTEM_TERMS) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "시스템 사전은 " + MAX_SYSTEM_TERMS + "개까지 등록할 수 있습니다");
            }
            entity = new SystemTerm(term, labelsJson, typesJson, adminId);
        } else {
            entity.setLabels(labelsJson);
            entity.setTermTypes(typesJson);
        }
        SystemTerm saved = termRepository.save(entity);
        auditRecorder.record(adminId, "SYSTEM_TERM_UPSERTED", "SYSTEM_TERM",
                Long.toString(saved.getId()), auditDetail(term, labels, types));
        return toResponse(saved);
    }

    /** 삭제(관리자) */
    @Transactional
    public void delete(long adminId, long termId) {
        adminGuard.requireAdmin(adminId);
        SystemTerm term = termRepository.findById(termId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TERM_NOT_FOUND));
        termRepository.delete(term);
        auditRecorder.record(adminId, "SYSTEM_TERM_DELETED", "SYSTEM_TERM",
                Long.toString(termId), Map.of("term", term.getTerm()));
    }

    /** 물리명 토큰 정규화 — 워크스페이스 용어와 같은 규칙(trim + 소문자, 공백 금지) */
    private String normalizeTerm(String raw) {
        String term = raw == null ? "" : raw.trim().toLowerCase();
        if (term.isEmpty() || term.chars().anyMatch(Character::isWhitespace)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "용어는 공백 없이 하나의 토큰이어야 합니다");
        }
        return term;
    }

    /** 언어→라벨 맵 검증·정규화 — 최소 1개 언어, 언어·라벨 모두 trim 후 비어있지 않고 ≤100자 */
    private Map<String, String> normalizeLabels(Map<String, String> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > MAX_LABEL_ENTRIES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "라벨은 1~" + MAX_LABEL_ENTRIES + "개 언어로 등록해야 합니다");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        raw.forEach((language, value) -> {
            String key = language == null ? "" : language.trim();
            String label = value == null ? "" : value.trim();
            if (key.isEmpty() || key.length() > 100 || label.isEmpty() || label.length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "언어와 라벨은 모두 비어 있지 않은 100자 이하여야 합니다");
            }
            labels.put(key, label);
        });
        return labels;
    }

    /** DBMS 종류별 타입 맵 검증·정규화 — 키는 database_types 등록 코드(활성 여부 불문 — 비활성 전환돼도
     *  기존 값을 보존한다), 값은 trim 후 빈 값은 그 종류 칸을 비운 것으로 버린다. 전부 비면 null */
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
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "데이터 타입은 100자 이하여야 합니다");
            }
            types.put(key, type);
        });
        return types.isEmpty() ? null : types;
    }

    private String writeMap(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            // 맵을 직접 직렬화하는 것이라 실패 경로가 없다 — 저장 직전 변환 실패는 요청 오류로 돌린다
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사전 값을 저장할 수 없습니다");
        }
    }

    private Map<String, String> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            // 우리가 쓴 JSON만 저장되므로 깨진 행은 없어야 한다 — 있다면 데이터 문제로 알리고 예외 처리한다
            throw new IllegalStateException("시스템 사전 맵 파싱 실패: " + json, ex);
        }
    }

    private Map<String, Object> auditDetail(String term, Map<String, String> labels, Map<String, String> types) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("term", term);
        detail.put("labels", labels);
        if (types != null) {
            detail.put("types", types);
        }
        return detail;
    }

    private SystemTermResponse toResponse(SystemTerm term) {
        return new SystemTermResponse(
                Long.toString(term.getId()),
                term.getTerm(),
                readMap(term.getLabels()),
                term.getTermTypes() == null ? null : readMap(term.getTermTypes()),
                term.getUpdatedAt());
    }
}
