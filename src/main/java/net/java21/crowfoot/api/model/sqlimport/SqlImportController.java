package net.java21.crowfoot.api.model.sqlimport;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * SQL Import API (08-core/02-model.md Section 1.12) — DDL 텍스트로 문서 만들기.
 * 미리보기(200)와 생성(201 + Location) 두 경로. 커넥션을 다루지 않으므로 모델 도메인에 둔다.
 * Gateway URL Rewrite 후 외부 계약은 /api/v1/core/* — 기존 인증 prefix 하라 화이트리스트 변경 없음.
 */
@RestController
@RequiredArgsConstructor
public class SqlImportController {

    private final SqlImportService sqlImportService;

    /** 미리보기 — 저장 없이 파싱·조립 결과만 (Editor 이상) */
    @PostMapping("/core/workspaces/{workspace-id}/models/sql-import/preview")
    public ApiResponse<SqlImportPreviewResponse> preview(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody SqlImportPreviewRequest request) {
        return ApiResponse.success(sqlImportService.preview(
                CurrentUserHolder.get().userId(), workspaceId, request));
    }

    /** 생성 — DDL로 신규 문서를 만들고 저장한다 (Editor 이상) */
    @PostMapping("/core/workspaces/{workspace-id}/models/sql-import")
    public ResponseEntity<ApiResponse<SqlImportResponse>> importDocument(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody SqlImportRequest request) {
        SqlImportResponse response = sqlImportService.importDocument(
                CurrentUserHolder.get().userId(), workspaceId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId
                        + "/models/" + response.model().modelId()))
                .body(ApiResponse.success(response));
    }
}
