package net.java21.crowfoot.api.showcase.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 사이트 쇼케이스 — 문서로 만든 사이트 한 건 (08-core/19-site-showcase.md Section 2). 문서당 한 건(model_id 유일).
 * 썸네일은 캡처 서비스가 찍은 800×500 JPEG를 그대로 둔다 — 목록은 이 컬럼을 읽지 않는 프로젝션으로 내린다.
 */
@Entity
@Table(name = "site_showcases", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class SiteShowcase {

    /** 신고가 이만큼 쌓이면 자동으로 숨긴다(Section 3.7) */
    public static final int AUTO_HIDE_REPORTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long modelId;

    private String url;

    private String title;

    private String description;

    private String siteName;

    private String faviconUrl;

    @Column(columnDefinition = "bytea")
    private byte[] thumbnail;

    private String thumbnailType;

    private Instant capturedAt;

    private String captureError;

    private Instant captureAttemptedAt;

    private boolean hidden;

    private Long hiddenBy;

    private Instant hiddenAt;

    private int reportCount;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public SiteShowcase(Long modelId, String url, String title, Long createdBy) {
        this.modelId = modelId;
        this.url = url;
        this.title = title;
        this.createdBy = createdBy;
    }

    /** 캡처 결과를 비운다 — 주소가 바뀌었는데 찍지 못했을 때 다른 사이트의 그림이 남지 않게(Section 3.2) */
    public void clearCapture() {
        this.thumbnail = null;
        this.thumbnailType = null;
        this.capturedAt = null;
        this.siteName = null;
        this.faviconUrl = null;
    }
}
