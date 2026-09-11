package net.java21.crowfoot.api.team.service;

import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.dto.UserRefResponse;
import net.java21.crowfoot.api.account.repository.UserQueryRepository;
import net.java21.crowfoot.api.account.repository.UserQueryRepository.UserCandidateResponse;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.team.domain.Team;
import net.java21.crowfoot.api.team.domain.TeamMember;
import net.java21.crowfoot.api.team.dto.AddTeamMemberRequest;
import net.java21.crowfoot.api.team.dto.CreateTeamRequest;
import net.java21.crowfoot.api.team.dto.CreateTeamResponse;
import net.java21.crowfoot.api.team.dto.TeamDetailResponse;
import net.java21.crowfoot.api.team.dto.TeamMemberResponse;
import net.java21.crowfoot.api.team.dto.TeamResponse;
import net.java21.crowfoot.api.team.repository.TeamMemberQueryRepository;
import net.java21.crowfoot.api.team.repository.TeamMemberRepository;
import net.java21.crowfoot.api.team.repository.TeamQueryRepository;
import net.java21.crowfoot.api.team.repository.TeamRepository;
import net.java21.crowfoot.api.workspace.domain.GranteeType;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipRepository;
import net.java21.crowfoot.common.ListApiResponse;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 팀 API (08-core/04-team.md) — 목록·생성·상세·정보 변경·멤버 관리·해체.
 *
 * <p>팀은 Workspace와 독립적인 멤버 관리 단위다 — 생성은 팀 구성만으로 끝나며
 * (Workspace를 만들지 않는다), Workspace 접근 연결은 별도의 멤버십 부여(granteeType=TEAM)로만 생긴다.
 * 해체는 멤버십 팀 부여 → 멤버 → 팀 순으로 물리 삭제하며 Workspace에는 영향을 주지 않는다.
 * 존재 은닉: 소속 아닌 팀은 404 TEAM_NOT_FOUND.
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private static final int CANDIDATE_DEFAULT_LIMIT = 10;
    private static final int CANDIDATE_MAX_LIMIT = 20;

    private final TeamRepository teamRepository;
    private final TeamQueryRepository teamQueryRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamMemberQueryRepository teamMemberQueryRepository;
    private final UserRepository userRepository;
    private final UserQueryRepository userQueryRepository;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final AuditRecorder auditRecorder;

    /** 팀 목록 — 소속 팀 전체(기본) 또는 내가 Owner인 팀만(ownedOnly=true) */
    @Transactional(readOnly = true)
    public ListApiResponse<TeamResponse> list(long userId, boolean ownedOnly) {
        List<Team> teams = ownedOnly
                ? teamQueryRepository.findOwnedByUserId(userId)
                : teamQueryRepository.findJoinedByUserId(userId);
        List<TeamResponse> responses = teams.stream()
                .map(team -> toResponse(team, userId))
                .toList();
        return ListApiResponse.of(responses);
    }

    /** 생성 — 팀 + Owner 멤버 행 한 트랜잭션. Workspace는 만들지 않는다(멤버십 부여로만 연결) */
    @Transactional
    public CreateTeamResponse create(long userId, CreateTeamRequest request) {
        Team team = teamRepository.save(new Team(request.name(), request.description(), userId));
        teamMemberRepository.save(new TeamMember(team.getId(), userId, null));

        auditRecorder.record(userId, "TEAM_CREATED", "TEAM",
                Long.toString(team.getId()), Map.of("teamId", Long.toString(team.getId())));
        return new CreateTeamResponse(Long.toString(team.getId()));
    }

    /** 상세 — 소속 멤버만 접근 */
    @Transactional(readOnly = true)
    public TeamDetailResponse get(long userId, long teamId) {
        requireMember(userId, teamId);
        Team team = requireTeam(teamId);
        return new TeamDetailResponse(
                Long.toString(team.getId()),
                team.getName(),
                team.getDescription(),
                team.getOwnerUserId().equals(userId),
                Long.toString(team.getOwnerUserId()),
                (int) teamMemberQueryRepository.countByTeamId(teamId),
                teamRole(team, userId),
                team.getCreatedAt());
    }

    /** 정보 변경(Owner만) — PATCH 의미론: 생략은 변경 없음, description 명시적 null은 클리어 */
    @Transactional
    public TeamDetailResponse patch(long userId, long teamId, JsonNode body) {
        requireOwner(userId, teamId, "팀 정보 변경 권한이 없습니다");
        Team team = requireTeam(teamId);

        if (body.has("name")) {
            String name = body.get("name").asText();
            if (name == null || name.isBlank() || name.length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이름은 1~100자여야 합니다");
            }
            team.setName(name);
        }
        if (body.has("description")) {
            team.setDescription(body.get("description").isNull() ? null : body.get("description").asText());
        }
        auditRecorder.record(userId, "TEAM_UPDATED", "TEAM", Long.toString(teamId), null);
        return get(userId, teamId);
    }

    /** 멤버 목록 — 소속 멤버만 접근, joined_at 순(Owner 생성 행이 첫 행) */
    @Transactional(readOnly = true)
    public ListApiResponse<TeamMemberResponse> members(long userId, long teamId) {
        requireMember(userId, teamId);
        Team team = requireTeam(teamId);
        List<TeamMemberResponse> responses = teamMemberQueryRepository.findByTeamId(teamId).stream()
                .map(member -> toResponse(team, member))
                .toList();
        return ListApiResponse.of(responses);
    }

    /** 멤버 추가(Owner만) — 201 header-only */
    @Transactional
    public Long addMember(long userId, long teamId, AddTeamMemberRequest request) {
        requireOwner(userId, teamId, null);
        requireTeam(teamId);

        long targetUserId = parseUserId(request.userId());
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다"));
        if (user.isWithdrawn()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다");
        }
        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, targetUserId)) {
            throw new BusinessException(ErrorCode.TEAM_MEMBER_DUPLICATED);
        }
        teamMemberRepository.save(new TeamMember(teamId, targetUserId, userId));
        auditRecorder.record(userId, "TEAM_MEMBER_ADDED", "TEAM",
                Long.toString(teamId), Map.of("userId", Long.toString(targetUserId)));
        return targetUserId;
    }

    /** 멤버 제외(Owner만) — Owner 본인은 해체 외에 제외 불가 */
    @Transactional
    public void removeMember(long userId, long teamId, long targetUserId) {
        requireOwner(userId, teamId, null);
        Team team = requireTeam(teamId);
        if (team.getOwnerUserId().equals(targetUserId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "팀 Owner은 해체 외에 제외할 수 없습니다");
        }
        long deleted = teamMemberQueryRepository.deleteByTeamIdAndUserId(teamId, targetUserId);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.TEAM_MEMBER_NOT_FOUND);
        }
        auditRecorder.record(userId, "TEAM_MEMBER_REMOVED", "TEAM",
                Long.toString(teamId), Map.of("userId", Long.toString(targetUserId)));
    }

    /** 팀 멤버 후보 검색(Owner만) — keyword 2자 이상, limit 기본 10·최대 20 */
    @Transactional(readOnly = true)
    public ListApiResponse<UserCandidateResponse> memberCandidates(
            long userId, long teamId, String keyword, Integer limit) {
        requireOwner(userId, teamId, null);
        requireTeam(teamId);

        String term = keyword == null ? null : keyword.trim();
        if (term == null || term.length() < 2) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "검색어는 2자 이상이어야 합니다");
        }
        int size = limit == null
                ? CANDIDATE_DEFAULT_LIMIT
                : Math.min(Math.max(limit, 1), CANDIDATE_MAX_LIMIT);
        return ListApiResponse.of(userQueryRepository.searchTeamCandidates(term, teamId, size));
    }

    /** 해체(Owner만) — 멤버십 팀 부여 회수 → 멤버 → 팀 물리 삭제. Workspace에는 영향 없음 */
    @Transactional
    public void dissolve(long userId, long teamId) {
        requireOwner(userId, teamId, null);
        requireTeam(teamId);
        auditRecorder.record(userId, "TEAM_DISSOLVED", "TEAM",
                Long.toString(teamId), Map.of("teamId", Long.toString(teamId)));
        workspaceMembershipRepository.deleteByGranteeTypeAndTeamId(GranteeType.TEAM, teamId);
        teamMemberRepository.deleteByTeamId(teamId);
        teamRepository.deleteById(teamId);
    }

    private Team requireTeam(long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TEAM_NOT_FOUND));
    }

    /** 소속 검사 — 비소속은 존재 은닉 404 */
    private void requireMember(long userId, long teamId) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new BusinessException(ErrorCode.TEAM_NOT_FOUND);
        }
    }

    /** Owner 검사 — 비소속 404, 소속이지만 Owner가 아니면 403 */
    private void requireOwner(long userId, long teamId, String deniedMessage) {
        requireMember(userId, teamId);
        Team team = requireTeam(teamId);
        if (!team.getOwnerUserId().equals(userId)) {
            throw deniedMessage == null
                    ? new BusinessException(ErrorCode.PERMISSION_DENIED)
                    : new BusinessException(ErrorCode.PERMISSION_DENIED, deniedMessage);
        }
    }

    private static String teamRole(Team team, long userId) {
        return team.getOwnerUserId().equals(userId) ? "OWNER" : "MEMBER";
    }

    private static long parseUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "userId는 숫자여야 합니다");
        }
    }

    private TeamResponse toResponse(Team team, long userId) {
        return new TeamResponse(
                Long.toString(team.getId()),
                team.getName(),
                team.getDescription(),
                team.getOwnerUserId().equals(userId),
                Long.toString(team.getOwnerUserId()),
                (int) teamMemberQueryRepository.countByTeamId(team.getId()),
                team.getCreatedAt());
    }

    private TeamMemberResponse toResponse(Team team, TeamMember member) {
        User user = userRepository.findById(member.getUserId()).orElse(null);
        UserRefResponse addedBy = member.getAddedBy() == null ? null
                : userRepository.findById(member.getAddedBy())
                        .map(u -> new UserRefResponse(Long.toString(u.getId()), u.getName()))
                        .orElse(null);
        return new TeamMemberResponse(
                Long.toString(member.getUserId()),
                user == null ? null : user.getName(),
                user == null ? null : user.getEmail(),
                teamRole(team, member.getUserId()),
                addedBy,
                member.getJoinedAt());
    }
}
