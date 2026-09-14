package net.java21.crowfoot.api.model.dto;

import java.time.Instant;

/**
 * 모델 버전 경량 응답 (1.9 협업 폴링) — 에디터가 주기적으로 물어 변경 여부를 감지한다.
 * content(최대 5MB)를 실어 나르지 않고 version만 내려준다.
 */
public record ModelVersionResponse(int version, Instant updatedAt) {
}
