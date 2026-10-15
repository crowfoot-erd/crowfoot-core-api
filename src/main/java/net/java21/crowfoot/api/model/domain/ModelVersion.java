package net.java21.crowfoot.api.model.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 문서 버전 기록 실체 (08-core/02-model.md Section 1.11) — content 저장마다 1:1 스냅샷.
 * change_summary는 웹 에디터가 만든 변경 요약 JSON(자동 메모), memo는 사용자 자유 메모.
 * tag·tagDescription은 버전 태그(08-core/13-model-versions.md Section 1.7) — 붙은 버전은 프루닝에서 빠진다.
 * createdAt은 생성자 주입 — 저장(1.5) bulk UPDATE가 기록하는 models.updated_at과
 * 같은 시각으로 맞춰 목록 정렬·표기가 어긋나지 않게 한다(@CreationTimestamp 미사용).
 */
@Entity
@Table(name = "model_versions", schema = "crowfoot_core",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_model_versions_model_version", columnNames = {"model_id", "version"}),
                @UniqueConstraint(name = "uq_model_versions_model_tag", columnNames = {"model_id", "tag"})},
        indexes = @Index(name = "ix_model_versions_model_id", columnList = "model_id"))
@Getter
@Setter
@NoArgsConstructor
public class ModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long modelId;

    /** 스냅샷 버전 — models.version과 1:1 (생성 직후 v0부터) */
    private long version;

    /** 해당 버전 시점의 Canonical 문서 JSON 전문 */
    private String content;

    /** 자동 변경 요약 JSON — 렌더는 클라이언트가(스키마 해석은 에디터 소유 원칙) */
    private String changeSummary;

    /** 사용자 메모(≤500자) — 자동 요약과 별개 */
    private String memo;

    /** 버전 태그(≤50자, 예: 1.0) — 문서 안에서 유일, 없으면 null */
    private String tag;

    /** 태그 설명(≤200자) — 태그가 없으면 null */
    private String tagDescription;

    private Long createdBy;

    private Instant createdAt;

    public ModelVersion(Long modelId, long version, String content, String changeSummary,
                        String memo, Long createdBy, Instant createdAt) {
        this.modelId = modelId;
        this.version = version;
        this.content = content;
        this.changeSummary = changeSummary;
        this.memo = memo;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }
}
