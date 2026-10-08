package net.java21.crowfoot.api.showcase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 사이트 등록·수정 (08-core/19-site-showcase.md Section 3.2). title이 비면 캡처한 제목(같은 주소면 지금 제목),
 * description은 null이면 캡처 값(같은 주소면 지금 값), 빈 문자열이면 지운다.
 */
public record SaveSiteRequest(
        @NotBlank @Size(max = 2000) String url,
        @Size(max = 200) String title,
        @Size(max = 500) String description) {
}
