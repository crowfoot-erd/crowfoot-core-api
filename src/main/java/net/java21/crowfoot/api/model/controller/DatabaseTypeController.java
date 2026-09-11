package net.java21.crowfoot.api.model.controller;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.model.dto.DatabaseTypeResponse;
import net.java21.crowfoot.api.model.service.DatabaseTypeService;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 데이터베이스 종류 API — 구현 경로 /core/**
 * (Gateway URL Rewrite 후 — 외부 계약은 /api/v1/core/*). 모델 생성 다이얼로그 드롭다운용.
 */
@RestController
@RequiredArgsConstructor
public class DatabaseTypeController {

    private final DatabaseTypeService databaseTypeService;

    /** 활성 종류 목록 — GET /core/providers와 같은 형태(활성만, 페이징 없음) */
    @GetMapping("/core/database-types")
    public ListApiResponse<DatabaseTypeResponse> listActive() {
        return ListApiResponse.of(databaseTypeService.listActive());
    }
}
