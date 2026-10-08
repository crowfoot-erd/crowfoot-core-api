package net.java21.crowfoot.api.showcase.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.showcase.dto.AdminSiteResponse;
import net.java21.crowfoot.api.showcase.dto.HideSiteRequest;
import net.java21.crowfoot.api.showcase.service.SiteShowcaseService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사이트 쇼케이스 관리자 API (08-core/19-site-showcase.md Section 3.8·3.9) — 관리자만(AdminGuard) */
@RestController
@RequiredArgsConstructor
public class AdminSiteShowcaseController {

    private final SiteShowcaseService showcaseService;

    /** 숨김 포함 목록 — 신고 수 내림차순 */
    @GetMapping("/core/admin/showcase/sites")
    public ListApiResponse<AdminSiteResponse> list(@RequestParam(required = false) Boolean hidden,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        Page<AdminSiteResponse> result = showcaseService.adminList(CurrentUserHolder.get().userId(), hidden, page, size);
        return ListApiResponse.paged(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    /** 숨김·보임 */
    @PatchMapping("/core/admin/showcase/sites/{site-id}")
    public ApiResponse<Void> setHidden(@PathVariable("site-id") long siteId,
                                       @Valid @RequestBody HideSiteRequest request) {
        showcaseService.setHidden(CurrentUserHolder.get().userId(), siteId, request.hidden());
        return ApiResponse.success();
    }
}
