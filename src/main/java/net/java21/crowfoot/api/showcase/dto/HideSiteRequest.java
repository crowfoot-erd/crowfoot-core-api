package net.java21.crowfoot.api.showcase.dto;

import jakarta.validation.constraints.NotNull;

/** 관리자 숨김·보임 (08-core/19-site-showcase.md Section 3.9) */
public record HideSiteRequest(@NotNull Boolean hidden) {
}
