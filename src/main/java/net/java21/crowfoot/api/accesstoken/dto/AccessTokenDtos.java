package net.java21.crowfoot.api.accesstoken.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import net.java21.crowfoot.api.account.dto.UserRefResponse;

/** 워크스페이스 액세스 토큰 API의 요청·응답 (08-core/18-access-token.md Section 3) */
public final class AccessTokenDtos {

    private AccessTokenDtos() {
    }

    /** 발급 요청 — 이름은 필수, 기간을 생략하면 무기한 */
    public record IssueRequest(
            @NotBlank @Size(max = 100) String name,
            @Min(1) @Max(365) Integer expiresInDays) {
    }

    /** 목록 항목이자 발급 응답 — token(원문)은 발급 응답에서만 나온다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(
            String tokenId,
            String name,
            String tokenPrefix,
            UserRefResponse createdBy,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant createdAt,
            String token) {
    }

    /** 내부 검증 요청 — 인증 서버가 토큰 원문의 SHA-256(16진수)을 보낸다 */
    public record VerifyRequest(@NotBlank @Size(min = 64, max = 64) String tokenHash) {
    }

    /** 내부 검증 응답 — 유효하지 않으면 active만 false로 온다(사유를 구분해 돌려주지 않는다) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerifyResponse(boolean active, String userId, String workspaceId, String tokenId, Instant expiresAt) {

        public static VerifyResponse inactive() {
            return new VerifyResponse(false, null, null, null, null);
        }
    }
}
