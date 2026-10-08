package net.java21.crowfoot.api.showcase.dto;

import jakarta.validation.constraints.Size;

/** 사이트 신고 (08-core/19-site-showcase.md Section 3.7) */
public record ReportSiteRequest(@Size(max = 500) String reason) {
}
