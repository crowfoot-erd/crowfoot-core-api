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
 * 문서 공유 링크 (08-core/02-model.md Section 1.10) — 토큰을 아는 누구나 기간 내 문서를 읽을 수 있다.
 * model_id는 논리 참조(모델 삭제 시 링크도 함께 사라진다 — FK CASCADE).
 * starts_at/ends_at은 각각 null 허용 — 시작일 null은 즉시 공유, 종료일 null은 무제한,
 * 둘 다 null이면 기간 제한 없는 상시 공유다. 문서당 여러 링크를 둘 수 있다(기간·용도별 발급).
 */
@Entity
@Table(name = "model_shares", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class ModelShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long modelId;

    /** 공개 조회 키 — URL 안전 마이크로 ID(base62, {@link ShareTokenGenerator}) */
    private String shareToken;

    /** 공유 시작 일시 — null이면 즉시 */
    private Instant startsAt;

    /** 공유 종료 일시 — null이면 무제한 */
    private Instant endsAt;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    public ModelShare(Long modelId, String shareToken, Instant startsAt, Instant endsAt, Long createdBy) {
        this.modelId = modelId;
        this.shareToken = shareToken;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdBy = createdBy;
    }
}
