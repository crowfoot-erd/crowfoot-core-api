package net.java21.crowfoot.api.domaintype.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.domaintype.dto.DomainTypeRequest;
import net.java21.crowfoot.api.domaintype.dto.DomainTypeResponse;
import net.java21.crowfoot.api.domaintype.service.DomainTypeService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 워크스페이스 도메인 타입 API (08-core/16-domain-type.md Section 3) — 구현 경로 /core/** (Gateway URL Rewrite 후).
 * 여러 컬럼이 함께 쓰는 타입 정의의 목록·만들기·고치기·지우기.
 */
@RestController
@RequiredArgsConstructor
public class DomainTypeController {

    private final DomainTypeService domainTypeService;

    /** 목록 — 멤버 전체, 페이징 메타 없는 목록 */
    @GetMapping("/core/workspaces/{workspace-id}/domain-types")
    public ListApiResponse<DomainTypeResponse> list(
            @PathVariable("workspace-id") long workspaceId) {
        return ListApiResponse.of(domainTypeService.list(CurrentUserHolder.get().userId(), workspaceId));
    }

    /** 만들기 — Editor 이상, 201 */
    @PostMapping("/core/workspaces/{workspace-id}/domain-types")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DomainTypeResponse> create(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody DomainTypeRequest request) {
        return ApiResponse.success(
                domainTypeService.create(CurrentUserHolder.get().userId(), workspaceId, request));
    }

    /** 고치기 — Editor 이상. baseVersion이 지금 버전과 다르면 409 */
    @PutMapping("/core/workspaces/{workspace-id}/domain-types/{domain-type-id}")
    public ApiResponse<DomainTypeResponse> update(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("domain-type-id") long domainTypeId,
            @Valid @RequestBody DomainTypeRequest request) {
        return ApiResponse.success(
                domainTypeService.update(CurrentUserHolder.get().userId(), workspaceId, domainTypeId, request));
    }

    /** 지우기 — Editor 이상, 본문 없음 */
    @DeleteMapping("/core/workspaces/{workspace-id}/domain-types/{domain-type-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("domain-type-id") long domainTypeId) {
        domainTypeService.delete(CurrentUserHolder.get().userId(), workspaceId, domainTypeId);
    }
}
