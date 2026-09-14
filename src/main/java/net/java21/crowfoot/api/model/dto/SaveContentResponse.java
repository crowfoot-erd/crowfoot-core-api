package net.java21.crowfoot.api.model.dto;

import java.time.Instant;

/** 문서 본체 저장 응답 (08-core/02-model.md Section 1.5) — 갱신된 버전·일시. */
public record SaveContentResponse(int version, Instant updatedAt) {
}
