package net.java21.crowfoot.api.model.dto;

import java.time.Instant;

/**
 * 공유 링크 생성 요청 (08-core/02-model.md Section 1.10) — startsAt·endsAt은 생략(null) 가능.
 * 시작일 null은 즉시 공유, 종료일 null은 무제한, 둘 다 null이면 상시 공유.
 * 종료일이 시작일보다 앞서면 400 INVALID_REQUEST.
 */
public record CreateShareRequest(Instant startsAt, Instant endsAt) {
}
