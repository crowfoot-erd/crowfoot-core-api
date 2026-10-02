package net.java21.crowfoot.api.domaintype.service;

import net.java21.crowfoot.api.account.service.AuditRecorder;
import net.java21.crowfoot.api.domaintype.domain.WorkspaceDomainType;
import net.java21.crowfoot.api.domaintype.dto.DomainTypeRequest;
import net.java21.crowfoot.api.domaintype.dto.DomainTypeResponse;
import net.java21.crowfoot.api.domaintype.repository.WorkspaceDomainTypeRepository;
import net.java21.crowfoot.api.term.repository.WorkspaceTermRepository;
import net.java21.crowfoot.api.workspace.service.RoleChecker;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * 도메인 타입 API 단위 테스트 (08-core/16-domain-type.md Section 3) —
 * 만들기(이름 중복·상한)·고치기(버전 충돌·무변경·이름 중복)·지우기(소속 검증)·감사·권한 게이트.
 */
@ExtendWith(MockitoExtension.class)
class DomainTypeServiceTest {

    @Mock
    private WorkspaceDomainTypeRepository domainTypeRepository;
    @Mock
    private WorkspaceTermRepository termRepository;
    @Mock
    private RoleChecker roleChecker;
    @Mock
    private AuditRecorder auditRecorder;

    private DomainTypeService service;

    @BeforeEach
    void setUp() {
        service = new DomainTypeService(domainTypeRepository, termRepository, roleChecker, auditRecorder);
    }

    /** 저장된 것과 같은 형태 — id·타임스탬프는 DB가 채우는 값이라 리플렉션으로 채운다 */
    private WorkspaceDomainType saved(long id, long workspaceId, String name, int version) {
        WorkspaceDomainType entity = new WorkspaceDomainType(workspaceId, name, 2L);
        entity.setDataType("VARCHAR");
        entity.setLength(100);
        entity.setNullable(true);
        entity.setVersion(version);
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "updatedAt", Instant.parse("2026-10-02T00:00:00Z"));
        return entity;
    }

    private static DomainTypeRequest request(String name, Integer length, Integer baseVersion) {
        return new DomainTypeRequest(name, "VARCHAR", length, null, null, true, null, null, baseVersion);
    }

    private void savesWithId(long id) {
        given(domainTypeRepository.save(any())).willAnswer((invocation) -> {
            WorkspaceDomainType entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", id);
            return entity;
        });
    }

    @Test
    @DisplayName("목록은 멤버면 읽는다 — 이름 순서 그대로 돌려준다")
    void listsForMembers() {
        given(domainTypeRepository.findByWorkspaceIdOrderByNameAsc(7L))
                .willReturn(List.of(saved(11L, 7L, "금액", 2), saved(12L, 7L, "이메일", 1)));

        List<DomainTypeResponse> list = service.list(2L, 7L);

        assertThat(list).extracting(DomainTypeResponse::name).containsExactly("금액", "이메일");
        assertThat(list.get(0).version()).isEqualTo(2);
        then(roleChecker).should().requireMember(2L, 7L);
    }

    @Test
    @DisplayName("만들기 — 이름을 다듬어 저장하고 version은 1, 빈 문자열은 null, nullable 생략은 true, 감사를 남긴다")
    void createsWithVersionOne() {
        given(domainTypeRepository.findByWorkspaceIdAndNameIgnoreCase(7L, "이메일")).willReturn(Optional.empty());
        given(domainTypeRepository.countByWorkspaceId(7L)).willReturn(0L);
        savesWithId(11L);

        DomainTypeResponse response = service.create(2L, 7L,
                new DomainTypeRequest("  이메일 ", "VARCHAR", 191, null, null, null, "  ", " 로그인에 쓰는 주소 ", null));

        assertThat(response.domainTypeId()).isEqualTo("11");
        assertThat(response.name()).isEqualTo("이메일");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.length()).isEqualTo(191);
        assertThat(response.nullable()).isTrue();
        assertThat(response.defaultValue()).isNull();
        assertThat(response.description()).isEqualTo("로그인에 쓰는 주소");
        then(roleChecker).should().requireEditor(2L, 7L);
        then(auditRecorder).should().record(2L, "WORKSPACE_DOMAIN_TYPE_CREATED", "WORKSPACE", "7",
                Map.of("name", "이메일", "dataType", "VARCHAR"));
    }

    @Test
    @DisplayName("만들기 — 대소문자만 다른 이름도 중복이다")
    void rejectsDuplicateNameOnCreate() {
        given(domainTypeRepository.findByWorkspaceIdAndNameIgnoreCase(7L, "EMAIL"))
                .willReturn(Optional.of(saved(11L, 7L, "email", 1)));

        assertThatThrownBy(() -> service.create(2L, 7L, request("EMAIL", 100, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        (ex) -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATED_NAME));
        then(domainTypeRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("만들기 — 200개 상한을 넘으면 거절한다")
    void rejectsOverLimit() {
        given(domainTypeRepository.findByWorkspaceIdAndNameIgnoreCase(7L, "이메일")).willReturn(Optional.empty());
        given(domainTypeRepository.countByWorkspaceId(7L)).willReturn(200L);

        assertThatThrownBy(() -> service.create(2L, 7L, request("이메일", 100, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        (ex) -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DOMAIN_TYPE_LIMIT_EXCEEDED));
        then(domainTypeRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("고치기 — 값이 바뀌면 version이 1 오르고 감사에 이전·새 버전을 남긴다")
    void updateBumpsVersion() {
        given(domainTypeRepository.findById(11L)).willReturn(Optional.of(saved(11L, 7L, "이메일", 3)));
        given(domainTypeRepository.findByWorkspaceIdAndNameIgnoreCase(7L, "이메일"))
                .willReturn(Optional.of(saved(11L, 7L, "이메일", 3)));
        given(domainTypeRepository.save(any())).willAnswer((invocation) -> invocation.getArgument(0));

        DomainTypeResponse response = service.update(2L, 7L, 11L, request("이메일", 255, 3));

        assertThat(response.version()).isEqualTo(4);
        assertThat(response.length()).isEqualTo(255);
        then(auditRecorder).should().record(2L, "WORKSPACE_DOMAIN_TYPE_UPDATED", "WORKSPACE", "7",
                Map.of("name", "이메일", "fromVersion", 3, "toVersion", 4));
    }

    @Test
    @DisplayName("고치기 — 값이 하나도 바뀌지 않았으면 version을 올리지 않고 저장도 감사도 하지 않는다")
    void updateWithoutChangeKeepsVersion() {
        given(domainTypeRepository.findById(11L)).willReturn(Optional.of(saved(11L, 7L, "이메일", 3)));
        given(domainTypeRepository.findByWorkspaceIdAndNameIgnoreCase(7L, "이메일")).willReturn(Optional.empty());

        DomainTypeResponse response = service.update(2L, 7L, 11L, request("이메일", 100, 3));

        assertThat(response.version()).isEqualTo(3);
        then(domainTypeRepository).should(never()).save(any());
        then(auditRecorder).should(never()).record(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("고치기 — baseVersion이 지금 버전과 다르면 VERSION_CONFLICT, 없으면 INVALID_REQUEST")
    void updateChecksBaseVersion() {
        given(domainTypeRepository.findById(11L)).willReturn(Optional.of(saved(11L, 7L, "이메일", 3)));

        assertThatThrownBy(() -> service.update(2L, 7L, 11L, request("이메일", 255, 2)))
                .isInstanceOfSatisfying(BusinessException.class,
                        (ex) -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        assertThatThrownBy(() -> service.update(2L, 7L, 11L, request("이메일", 255, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        (ex) -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        then(domainTypeRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("고치기 — 다른 도메인 타입이 쓰는 이름으로는 바꿀 수 없다")
    void updateRejectsNameOfAnother() {
        given(domainTypeRepository.findById(11L)).willReturn(Optional.of(saved(11L, 7L, "이메일", 1)));
        given(domainTypeRepository.findByWorkspaceIdAndNameIgnoreCase(7L, "금액"))
                .willReturn(Optional.of(saved(12L, 7L, "금액", 1)));

        assertThatThrownBy(() -> service.update(2L, 7L, 11L, request("금액", 100, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        (ex) -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATED_NAME));
    }

    @Test
    @DisplayName("지우기 — 다른 워크스페이스의 도메인 타입은 없는 것으로 본다")
    void deleteChecksOwnership() {
        given(domainTypeRepository.findById(11L)).willReturn(Optional.of(saved(11L, 99L, "이메일", 1)));

        assertThatThrownBy(() -> service.delete(2L, 7L, 11L))
                .isInstanceOfSatisfying(BusinessException.class,
                        (ex) -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DOMAIN_TYPE_NOT_FOUND));
        then(domainTypeRepository).should(never()).delete(any());
    }

    @Test
    @DisplayName("지우기 — 지우고 감사에 이름을 남긴다")
    void deletesAndAudits() {
        WorkspaceDomainType entity = saved(11L, 7L, "이메일", 1);
        given(domainTypeRepository.findById(11L)).willReturn(Optional.of(entity));

        service.delete(2L, 7L, 11L);

        then(domainTypeRepository).should().delete(entity);
        // 이것을 가리키던 사전 용어는 연결만 풀린다(08-core/01-workspace.md Section 4.6)
        then(termRepository).should().clearDomainType(7L, 11L);
        then(auditRecorder).should().record(2L, "WORKSPACE_DOMAIN_TYPE_DELETED", "WORKSPACE", "7",
                Map.of("name", "이메일"));
    }

    @Test
    @DisplayName("쓰기는 Editor 미만이면 막힌다 — 저장소에 닿지 않는다")
    void writeRequiresEditor() {
        willThrow(new BusinessException(ErrorCode.PERMISSION_DENIED)).given(roleChecker).requireEditor(3L, 7L);

        assertThatThrownBy(() -> service.create(3L, 7L, request("이메일", 100, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(3L, 7L, 11L, request("이메일", 100, 1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(3L, 7L, 11L)).isInstanceOf(BusinessException.class);
        then(domainTypeRepository).shouldHaveNoInteractions();
        then(termRepository).shouldHaveNoInteractions();
    }
}
