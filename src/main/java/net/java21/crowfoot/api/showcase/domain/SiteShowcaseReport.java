package net.java21.crowfoot.api.showcase.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** 사이트 쇼케이스 신고 — 사용자당 사이트 하나에 한 번 (08-core/19-site-showcase.md Section 3.7) */
@Entity
@Table(name = "site_showcase_reports", schema = "crowfoot_core")
@Getter
@NoArgsConstructor
public class SiteShowcaseReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long showcaseId;

    private Long userId;

    private String reason;

    @CreationTimestamp
    private Instant createdAt;

    public SiteShowcaseReport(Long showcaseId, Long userId, String reason) {
        this.showcaseId = showcaseId;
        this.userId = userId;
        this.reason = reason;
    }
}
