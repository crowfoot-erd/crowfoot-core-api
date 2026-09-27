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
 * 공유 문서 반응(좋아요) (08-core/02-model.md Section 1.10.6) — 1링크×1방문자 1행.
 * UNIQUE(share_id, visitor_key)가 링크당 방문자 1회를 보장하고, 다시 누르면 행 삭제로 토글된다.
 * visitor_key는 서버 발급 쿠키 crowfoot_share_actor의 UUID 값(계정 없는 근사 식별).
 * 삽입은 {@code insertIgnoreConflict}(ON CONFLICT DO NOTHING) 원자 경로로만 한다.
 * 링크 철회·문서 삭제 시 FK CASCADE로 함께 사라진다.
 */
@Entity
@Table(name = "model_share_reactions", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class ModelShareReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shareId;

    /** 방문자 식별 키 — 쿠키 crowfoot_share_actor 값 */
    private String visitorKey;

    @CreationTimestamp
    private Instant createdAt;
}
