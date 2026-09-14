package net.java21.crowfoot.api.model.dto;

import java.util.List;

/**
 * 포워드 엔지니어링 배포 결과 (08-core/02-model.md Section 1.8) — 문장별 성공/실패.
 * 한 문장이 실패해도 나머지를 실행한 뒤 전체 결과를 보고한다(부분 실패 리포트).
 */
public record ModelDeployResponse(
        int executedCount,
        int failedCount,
        List<Statement> statements,
        List<DdlWarningResponse> warnings) {

    public record Statement(String sql, boolean ok, String error) {
    }
}
