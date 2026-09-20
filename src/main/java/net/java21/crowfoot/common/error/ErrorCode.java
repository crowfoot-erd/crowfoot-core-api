package net.java21.crowfoot.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 에러 코드 체계 (01-architecture/api-design.md Section 5.4 + 08-core/00-overview.md Section 4
 * + 08-core/03-membership.md Section 2 + 08-core/04-team.md Section 2).
 *
 * <p>resultMessage는 기본 진단 문구이며, 도메인 컨텍스트별 문구(예: "설정 변경 권한이 없습니다")는
 * {@link BusinessException} 생성 시 덮어쓸 수 있다.
 */
@Getter
public enum ErrorCode {

    // 공통 (접두사 없음)
    SUCCESS(HttpStatus.OK, "SUCCESS", "SUCCESS"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "리소스를 찾을 수 없습니다"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청 한도를 초과했습니다"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "서비스를 일시적으로 사용할 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"),

    // AUTH_
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "토큰이 유효하지 않습니다"),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_EXPIRED", "토큰이 만료되었습니다"),
    AUTH_SESSION_REVOKED(HttpStatus.CONFLICT, "AUTH_SESSION_REVOKED", "세션이 무효화되었습니다"),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "권한이 없습니다"),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "REFRESH_TOKEN_NOT_FOUND", "Refresh 토큰을 찾을 수 없습니다"),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "세션을 찾을 수 없습니다"),
    USER_WITHDRAWN(HttpStatus.CONFLICT, "USER_WITHDRAWN", "탈퇴한 계정입니다"),
    WITHDRAW_BLOCKED(HttpStatus.CONFLICT, "WITHDRAW_BLOCKED", "남아 있는 소유권을 정리한 후 탈퇴할 수 있습니다"),

    // Workspace
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다"),

    // Membership (08-core/03-membership.md Section 2)
    MEMBERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND", "멤버십 정보를 찾을 수 없습니다"),
    MEMBERSHIP_DUPLICATED(HttpStatus.CONFLICT, "MEMBERSHIP_DUPLICATED", "이미 부여된 대상입니다"),
    LAST_OWNER_PROTECTED(HttpStatus.CONFLICT, "LAST_OWNER_PROTECTED", "마지막 Owner는 강등할 수 없습니다"),

    // Team (08-core/04-team.md Section 2)
    TEAM_NOT_FOUND(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"),
    TEAM_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "TEAM_MEMBER_NOT_FOUND", "팀 멤버가 아닙니다"),
    TEAM_MEMBER_DUPLICATED(HttpStatus.CONFLICT, "TEAM_MEMBER_DUPLICATED", "이미 팀 멤버입니다"),

    // Model (08-core/02-model.md Section 1)
    MODEL_NOT_FOUND(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "모델을 찾을 수 없습니다"),
    DUPLICATED_NAME(HttpStatus.CONFLICT, "DUPLICATED_NAME", "이미 존재하는 이름입니다"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "VERSION_CONFLICT", "다른 클라이언트가 먼저 저장했습니다"),
    MODEL_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "MODEL_VERSION_NOT_FOUND", "버전 기록을 찾을 수 없습니다"),

    // Share (08-core/02-model.md Section 1.10)
    SHARE_NOT_FOUND(HttpStatus.NOT_FOUND, "SHARE_NOT_FOUND", "공유 링크를 찾을 수 없습니다"),
    SHARE_INACTIVE(HttpStatus.GONE, "SHARE_INACTIVE", "공유 기간이 아니거나 만료되었습니다"),

    // Connection (08-core/06-connection.md Section 3.7)
    CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND", "커넥션을 찾을 수 없습니다"),
    INVALID_DBMS_TYPE(HttpStatus.BAD_REQUEST, "INVALID_DBMS_TYPE", "지원하지 않는 데이터베이스 종류입니다"),
    CONNECTION_UNREACHABLE(HttpStatus.BAD_GATEWAY, "CONNECTION_UNREACHABLE", "데이터베이스에 접속할 수 없습니다"),
    REVERSE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "REVERSE_FAILED", "스키마를 문서로 만들지 못했습니다"),

    // Managed database (08-core/07-managed-database.md Section 3.7)
    MANAGED_INSTANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "MANAGED_INSTANCE_NOT_FOUND", "매니지드 인스턴스를 찾을 수 없습니다"),
    MANAGED_INSTANCE_UNREACHABLE(HttpStatus.BAD_GATEWAY, "MANAGED_INSTANCE_UNREACHABLE", "매니지드 인스턴스에 접속할 수 없습니다"),
    MANAGED_INSTANCE_IN_USE(HttpStatus.CONFLICT, "MANAGED_INSTANCE_IN_USE", "발급이 남아 있는 인스턴스는 삭제할 수 없습니다"),
    MANAGED_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "MANAGED_LIMIT_EXCEEDED", "발급 한도에 도달했습니다"),
    MANAGED_PROVISION_FAILED(HttpStatus.BAD_GATEWAY, "MANAGED_PROVISION_FAILED", "발급 스키마 프로비저닝에 실패했습니다"),
    MANAGED_DATABASE_NOT_FOUND(HttpStatus.NOT_FOUND, "MANAGED_DATABASE_NOT_FOUND", "매니지드 발급을 찾을 수 없습니다"),

    // Community (08-core/08-community.md Section 2)
    COMMUNITY_POST_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMUNITY_POST_NOT_FOUND", "게시글을 찾을 수 없습니다"),
    COMMUNITY_COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMUNITY_COMMENT_NOT_FOUND", "코멘트를 찾을 수 없습니다"),
    COMMUNITY_COMMENT_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "COMMUNITY_COMMENT_NOT_ALLOWED",
            "이 게시판에서는 코멘트를 사용할 수 없습니다");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
