package net.java21.crowfoot.api.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 알림 응답 (08-core/11-notification.md Section 5) — 목록·드롭다운이 같은 형태로 쓴다.
 * 알림 문구는 서버에 두지 않는다 — 웹이 type 기준 i18n(shell.notifications.type.*)로 렌더한다.
 * actorUserId는 게스트 댓글이면 생략되고(null), actorDisplayName은 서버가 조립한 단일 표시명
 * (회원 = users.name, 게스트 = 별명 스냅샷)이다. workspaceId·modelId는 문서 이동 링크용.
 * null 필드는 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationResponse(
        String id,
        String type,
        String actorUserId,
        String actorDisplayName,
        String modelId,
        String modelName,
        String workspaceId,
        boolean read,
        Instant createdAt) {
}
