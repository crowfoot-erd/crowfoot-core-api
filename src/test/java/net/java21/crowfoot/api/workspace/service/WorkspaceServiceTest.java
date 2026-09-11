package net.java21.crowfoot.api.workspace.service;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.workspace.domain.Workspace;
import net.java21.crowfoot.api.workspace.dto.CreateWorkspaceRequest;
import net.java21.crowfoot.api.workspace.dto.WorkspaceResponse;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipQueryRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceMembershipRepository;
import net.java21.crowfoot.api.workspace.repository.WorkspaceRepository;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Workspace 실체 API 단위 테스트 (08-core/01-workspace.md) —
 * 생성·수정·삭제의 트랜잭션 대칭(OWNER 멤버십 세트/전량 정리)과 존재 은닉을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private WorkspaceMembershipRepository workspaceMembershipRepository;
    @Mock
    private WorkspaceMembershipQueryRepository workspaceMembershipQueryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private WorkspaceService workspaceService;

    @Test
    @DisplayName("개인 소유 생성은 Workspace와 OWNER 멤버십을 같이 저장한다")
    void createPersistsWorkspaceAndOwnerMembership() {
        // given
        given(workspaceRepository.save(any(Workspace.class))).willAnswer(inv -> {
            Workspace saved = inv.getArgument(0);
            saved.setId(77L);
            return saved;
        });
        given(userRepository.findById(7L)).willReturn(Optional.of(creator()));
        given(workspaceMembershipQueryRepository.countDistinctMembers(77L)).willReturn(1L);

        // when
        WorkspaceResponse response =
                workspaceService.create(7L, new CreateWorkspaceRequest("ERD 작업실", "설명"));

        // then
        assertThat(response.workspaceId()).isEqualTo("77");
        assertThat(response.memberCount()).isEqualTo(1);
        verify(workspaceMembershipRepository).save(any());
        verify(auditRecorder).record(eq(7L), eq("WORKSPACE_CREATED"), eq("WORKSPACE"), eq("77"), anyMap());
    }

    @Test
    @DisplayName("비멤버의 조회는 존재 은닉으로 404 WORKSPACE_NOT_FOUND")
    void getRejectsNonMemberAs404() {
        // given
        willThrow(new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND))
                .given(roleChecker).requireMember(7L, 77L);

        // when & then
        assertThatThrownBy(() -> workspaceService.get(7L, 77L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
        verify(workspaceRepository, never()).findById(77L);
    }

    @Test
    @DisplayName("설정 변경은 name만 바꾸고, description 명시적 null은 클리어한다")
    void patchUpdatesNameAndClearsDescription() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("name", "새 이름");
        body.putNull("description");

        // when
        WorkspaceResponse response = workspaceService.patch(7L, 77L, body);

        // then
        assertThat(response.name()).isEqualTo("새 이름");
        assertThat(response.description()).isNull();
        verify(auditRecorder).record(eq(7L), eq("WORKSPACE_UPDATED"), eq("WORKSPACE"), eq("77"), eq(null));
    }

    @Test
    @DisplayName("설정 변경에서 빈 name은 400 INVALID_REQUEST")
    void patchRejectsBlankName() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("name", "  ");

        // when & then
        assertThatThrownBy(() -> workspaceService.patch(7L, 77L, body))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("삭제는 멤버십 전량 DELETE 후 Workspace를 물리 삭제한다")
    void deleteRemovesMembershipsThenWorkspace() {
        // given
        given(workspaceRepository.findById(77L)).willReturn(Optional.of(workspace()));

        // when
        workspaceService.delete(7L, 77L);

        // then
        InOrder order = inOrder(workspaceMembershipRepository, workspaceRepository);
        order.verify(workspaceMembershipRepository).deleteByWorkspaceId(77L);
        order.verify(workspaceRepository).deleteById(77L);
        verify(auditRecorder).record(eq(7L), eq("WORKSPACE_DELETED"), eq("WORKSPACE"), eq("77"), eq(null));
    }

    private Workspace workspace() {
        Workspace workspace = new Workspace("ERD 작업실", "설명", 7L, false, 7L);
        workspace.setId(77L);
        return workspace;
    }

    private User creator() {
        User user = new User("alice@x.com", "앨리스", false);
        user.setId(7L);
        return user;
    }
}
