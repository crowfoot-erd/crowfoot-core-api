package net.java21.crowfoot.api.model.dto;

/**
 * 공유 문서 반응 토글 결과 (08-core/02-model.md Section 1.10.6) — 카운터는 트랜잭션 시작 시점
 * 스냅숏에 이번 토글(±1)을 반영한 정착값(원자 갱신은 DB가 보장).
 */
public record ShareReactionResponse(
        long reactionCount,
        boolean reacted) {
}
