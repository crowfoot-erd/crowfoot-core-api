package net.java21.crowfoot.api.model.dto;

/**
 * DDL 생성 경고 1건 (05-editor/04-dbms-engineering.md §3.1 — 생성 전 Capability 선표시).
 *
 * @param code    COMMON_DIALECT · VALIDATION · EMPTY_TABLE
 * @param message 표시 문구
 */
public record DdlWarningResponse(String code, String message) {
}
