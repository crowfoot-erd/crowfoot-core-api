package net.java21.crowfoot.api.term.service;

import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 시스템 사전(SystemTermService)과 워크스페이스 사전(TermService)이 함께 쓰는 맵 규칙 —
 * DBMS 종류별 타입 맵 검증·정규화와 JSON 문자열 저장 형식(언어→라벨, 종류→타입).
 */
final class TermTypeMaps {

    private TermTypeMaps() {
    }

    /** DBMS 종류별 타입 맵 검증·정규화 — 키는 database_types 등록 코드(활성 여부 불문 — 비활성 전환돼도
     *  기존 값을 보존한다), 값은 trim 후 빈 값은 그 종류 칸을 비운 것으로 버린다. 전부 비면 빈 맵(저장은 null) */
    static Map<String, String> normalizeTypes(Map<String, String> raw, DatabaseTypeRepository databaseTypeRepository) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
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
        return types;
    }

    static String writeMap(ObjectMapper objectMapper, Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            // 맵을 직접 직렬화하는 것이라 실패 경로가 없다 — 저장 직전 변환 실패는 요청 오류로 돌린다
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, ex, "detail.term.value.unsavable");
        }
    }

    /** 저장된 맵 JSON 읽기 — 우리가 쓴 JSON만 저장되므로 깨진 행은 없어야 한다. 있다면 데이터 문제로
     *  알린다(failureLabel은 메시지 앞머리 — 사전 종류 구분) */
    static Map<String, String> readMap(ObjectMapper objectMapper, String json, String failureLabel) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException(failureLabel + " 맵 파싱 실패: " + json, ex);
        }
    }
}
