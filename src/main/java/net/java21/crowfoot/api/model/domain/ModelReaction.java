package net.java21.crowfoot.api.model.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 문서 반응(좋아요) (08-core/02-model.md Section 1.10.6) — 1문서×1회원 1행.
 * 문서 단위다(2026-09-28 재설계 — 어느 링크에서 남기든·댓글 탭에서 남기든 같은 반응).
 * UNIQUE(model_id, user_id)가 문서당 회원 1회를 보장하고, 다시 누르면 행 삭제로 토글된다.
 * 반응은 회원전용(2026-09-27 재설계) — 신원은 계정 user_id(논리 참조 users.id, FK 없음 — 그룹 경계 관례).
 * 삽입은 {@code insertIgnoreConflict}(ON CONFLICT DO NOTHING) 원자 경로로만 한다.
 * 문서 삭제 시 FK CASCADE로 함께 사라진다.
 */
@Entity
@Table(name = "model_reactions", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class ModelReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 반응 대상 문서 — 문서 단위 앵커(2026-09-28 링크 단위에서 이관) */
    private Long modelId;

    /** 반응한 회원 — 논리 참조 users.id */
    private Long userId;

    @CreationTimestamp
    private Instant createdAt;
}
