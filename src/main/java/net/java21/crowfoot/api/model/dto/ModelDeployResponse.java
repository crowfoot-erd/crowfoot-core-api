package net.java21.crowfoot.api.model.dto;

import java.util.List;

/**
 * 포워드 엔지니어링 배포 결과 (08-core/02-model.md Section 1.8) — 문장별 성공/실패.
 * 한 문장이 실패해도 나머지를 실행한 뒤 전체 결과를 보고한다(부분 실패 리포트).
 * skippedDestructive는 마이그레이션 실행(Section 1.15)이 건너뛴 삭제 문장의 수다.
 */
public record ModelDeployResponse(
        int executedCount,
        int failedCount,
        List<Statement> statements,
        List<DdlWarningResponse> warnings,
        int skippedDestructive) {

    /** 삭제 문장을 건너뛰지 않은 결과 — 최초 배포에는 삭제 문장이 없다 */
    public ModelDeployResponse(int executedCount, int failedCount, List<Statement> statements, List<DdlWarningResponse> warnings) {
        this(executedCount, failedCount, statements, warnings, 0);
    }


    public record Statement(String sql, boolean ok, String error) {
    }
}
