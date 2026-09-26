package net.java21.crowfoot.api.internal.controller;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.internal.dto.CollabMembershipResponse;
import net.java21.crowfoot.api.internal.dto.CollabProfileResponse;
import net.java21.crowfoot.api.internal.service.InternalCollabService;
import net.java21.crowfoot.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 전용 API — 협업 서버용 멤버십·프로필 조회 (05-editor/03-collaboration.md Section 2.1).
 * Gateway 라우팅 제외(외부 경로 /api/v1/core/**와 무관), 내부망에서만 연다(Internal* 관례).
 */
@RestController
@RequiredArgsConstructor
public class InternalCollabController {

    private final InternalCollabService internalCollabService;

    /** 룸(=Model) 인가용 — 소속 Workspace와 유효 역할(비멤버 "NONE") */
    @GetMapping("/internal/core/collab/models/{modelId}/membership")
    public ApiResponse<CollabMembershipResponse> membership(@PathVariable("modelId") long modelId,
                                                            @RequestParam("userId") long userId) {
        return ApiResponse.success(internalCollabService.membership(modelId, userId));
    }

    /** 표시 프로필용 — introspection 클레임에 없는 이름·아바타·GitHub 핸들 */
    @GetMapping("/internal/core/collab/users/{userId}/profile")
    public ApiResponse<CollabProfileResponse> profile(@PathVariable("userId") long userId) {
        return ApiResponse.success(internalCollabService.profile(userId));
    }
}
