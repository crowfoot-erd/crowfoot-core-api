package net.java21.crowfoot.api.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 템플릿 복제 요청 (08-core/09-templates.md Section 2.2) — templateModelId는 템플릿 워크스페이스
 * 소속 문서여야 한다(다른 워크스페이스의 문서면 TEMPLATE_NOT_FOUND 존재 은닉).
 * name은 선택 — 생략·빈 값이면 원본 이름을 쓰고, 대상 워크스페이스 내 중복이면 409 DUPLICATED_NAME.
 */
public record CloneFromTemplateRequest(
        @NotNull Long templateModelId,
        @Size(max = 100) String name
) {
}
