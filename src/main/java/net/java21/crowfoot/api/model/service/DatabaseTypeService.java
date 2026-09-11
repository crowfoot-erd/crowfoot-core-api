package net.java21.crowfoot.api.model.service;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.model.domain.DatabaseType;
import net.java21.crowfoot.api.model.dto.DatabaseTypeResponse;
import net.java21.crowfoot.api.model.repository.DatabaseTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 데이터베이스 종류 코드 조회 — ProviderService.listActive와 같은 패턴(활성만). */
@Service
@RequiredArgsConstructor
public class DatabaseTypeService {

    private final DatabaseTypeRepository databaseTypeRepository;

    /** 활성 종류 목록(코드 asc) — 모델 생성 다이얼로그 드롭다운 */
    @Transactional(readOnly = true)
    public List<DatabaseTypeResponse> listActive() {
        return databaseTypeRepository.findByIsActiveTrueOrderByCodeAsc().stream()
                .map(type -> new DatabaseTypeResponse(type.getCode(), type.getDisplayName()))
                .toList();
    }
}
