package net.java21.crowfoot.api.term.repository;

import jakarta.persistence.EntityManager;
import net.java21.crowfoot.api.term.domain.WorkspaceTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 용어 리포지토리 테스트 (08-core/01-workspace.md Section 4.6) — 도메인 타입 연결 컬럼의 매핑과
 * 연결 풀기 질의를 실제 스키마로 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkspaceTermRepositoryTest {

    @Autowired
    private WorkspaceTermRepository repository;
    @Autowired
    private EntityManager entityManager;

    private WorkspaceTerm term(long workspaceId, String name, Long domainTypeId) {
        WorkspaceTerm entity = new WorkspaceTerm(workspaceId, name, name, null, 2L);
        entity.setDomainTypeId(domainTypeId);
        return repository.saveAndFlush(entity);
    }

    @Test
    @DisplayName("연결 풀기 — 그 워크스페이스에서 그 도메인 타입을 가리키던 용어만 풀린다. 용어는 남는다")
    void clearsOnlyMatchingLinks() {
        WorkspaceTerm linked = term(7L, "user_email", 11L);
        WorkspaceTerm other = term(7L, "amount", 12L);
        WorkspaceTerm foreign = term(8L, "user_email", 11L);
        WorkspaceTerm word = term(7L, "user", null);

        int cleared = repository.clearDomainType(7L, 11L);
        entityManager.clear();

        assertThat(cleared).isEqualTo(1);
        assertThat(repository.findById(linked.getId()).orElseThrow().getDomainTypeId()).isNull();
        assertThat(repository.findById(other.getId()).orElseThrow().getDomainTypeId()).isEqualTo(12L);
        assertThat(repository.findById(foreign.getId()).orElseThrow().getDomainTypeId()).isEqualTo(11L);
        assertThat(repository.findById(word.getId())).isPresent();
    }
}
