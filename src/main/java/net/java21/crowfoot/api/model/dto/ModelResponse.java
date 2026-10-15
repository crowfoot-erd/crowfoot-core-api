package net.java21.crowfoot.api.model.dto;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.model.domain.Model;

import java.time.Instant;

/**
 * 모델 응답 — 생성·상세 (08-core/02-model.md Section 1 "모델 응답 필드" 전체, content 포함).
 * 목록은 content를 제외한 {@link ModelSummaryResponse}를 쓴다.
 */
public record ModelResponse(
        String modelId,
        String workspaceId,
        String name,
        String description,
        String databaseType,
        String sourceConnectionId,
        String content,
        int version,
        UserRefResponse createdBy,
        Instant createdAt,
        Instant updatedAt
) {

    /** 엔티티 → 응답 — 생성자(creator)는 호출부가 조회해 넘긴다(탈퇴 등으로 없으면 null → createdBy null).
     *  문서를 만드는 경로(생성·리버스·SQL 가져오기·템플릿 복제)가 같은 모양을 내려주도록 한곳에 둔다 */
    public static ModelResponse of(Model model, User creator) {
        UserRefResponse createdBy = creator == null
                ? null
                : new UserRefResponse(Long.toString(creator.getId()), creator.getName());
        return new ModelResponse(
                Long.toString(model.getId()),
                Long.toString(model.getWorkspaceId()),
                model.getName(),
                model.getDescription(),
                model.getDatabaseType(),
                model.getSourceConnectionId() == null ? null : Long.toString(model.getSourceConnectionId()),
                model.getContent(),
                (int) model.getVersion(),
                createdBy,
                model.getCreatedAt(),
                model.getUpdatedAt());
    }
}
