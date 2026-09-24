package net.java21.crowfoot.api.term.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.term.dto.SystemTermResponse;
import net.java21.crowfoot.api.term.dto.UpsertSystemTermRequest;
import net.java21.crowfoot.api.term.service.SystemTermService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시스템 사전 API (08-core/01-workspace.md Section 4.5) — 구현 경로 /core/** (Gateway URL Rewrite 후).
 * 전 워크스페이스가 공유하는 전역 용어 사전 — 사용자는 읽기만, 등록·수정·삭제는 관리자(/core/admin).
 */
@RestController
@RequiredArgsConstructor
public class SystemTermController {

    private final SystemTermService systemTermService;

    /** 시스템 사전 목록 — 인증된 사용자 전체(역할 검사 없음). 페이징(page 1부터·size ≤100)과
     *  letter(알파벳 이니셜, '#'는 알파벳 외)·keyword(토큰·labels 값 부분 일치) 필터를 받는다 */
    @GetMapping("/core/system-terms")
    public ListApiResponse<SystemTermResponse> list(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "letter", required = false) String letter,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return systemTermService.list(page, size, letter, keyword);
    }

    /** 관리 목록 — 관리자만(AdminGuard). 같은 페이징·필터 파라미터를 쓰고
     *  감사(ADMIN_SYSTEM_TERMS_LISTED)는 서비스가 남긴다 */
    @GetMapping("/core/admin/system-terms")
    public ListApiResponse<SystemTermResponse> adminList(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "letter", required = false) String letter,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return systemTermService.adminList(CurrentUserHolder.get().userId(), page, size, letter, keyword);
    }

    /** 등록·수정(upsert) — 관리자만. 자연키라 신규·수정 구분 없이 항상 200 */
    @PostMapping("/core/admin/system-terms")
    public ApiResponse<SystemTermResponse> upsert(
            @Valid @RequestBody UpsertSystemTermRequest request) {
        return ApiResponse.success(
                systemTermService.upsert(CurrentUserHolder.get().userId(), request));
    }

    /** 삭제 — 관리자만, 본문 없음 */
    @DeleteMapping("/core/admin/system-terms/{term-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("term-id") long termId) {
        systemTermService.delete(CurrentUserHolder.get().userId(), termId);
    }
}
