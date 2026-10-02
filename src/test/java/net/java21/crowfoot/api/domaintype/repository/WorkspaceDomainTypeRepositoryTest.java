package net.java21.crowfoot.api.domaintype.repository;

import net.java21.crowfoot.api.domaintype.domain.WorkspaceDomainType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도메인 타입 리포지토리 테스트 (08-core/16-domain-type.md Section 2) — 엔티티 매핑(컬럼 이름)과
 * 파생 질의(이름 순서·대소문자 무시 조회·워크스페이스 범위)를 실제 스키마로 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkspaceDomainTypeRepositoryTest {

    @Autowired
    private WorkspaceDomainTypeRepository repository;

    private WorkspaceDomainType domainType(long workspaceId, String name) {
        WorkspaceDomainType entity = new WorkspaceDomainType(workspaceId, name, 2L);
        entity.setDataType("DECIMAL");
        entity.setPrecision(15);
        entity.setScale(2);
        entity.setNullable(false);
        entity.setDefaultValue("0");
        return entity;
    }

    @Test
    @DisplayName("저장하고 다시 읽는다 — 길이·정밀도·스케일·기본값·version이 그대로다")
    void savesAndReads() {
        WorkspaceDomainType saved = repository.saveAndFlush(domainType(7L, "금액"));

        WorkspaceDomainType found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getDataType()).isEqualTo("DECIMAL");
        assertThat(found.getLength()).isNull();
        assertThat(found.getPrecision()).isEqualTo(15);
        assertThat(found.getScale()).isEqualTo(2);
        assertThat(found.isNullable()).isFalse();
        assertThat(found.getDefaultValue()).isEqualTo("0");
        assertThat(found.getVersion()).isEqualTo(1);
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("목록은 그 워크스페이스 것만 이름 순서로, 이름 조회는 대소문자를 무시한다")
    void scopesByWorkspace() {
        repository.saveAndFlush(domainType(7L, "money"));
        repository.saveAndFlush(domainType(7L, "Email"));
        repository.saveAndFlush(domainType(8L, "email"));

        assertThat(repository.findByWorkspaceIdOrderByNameAsc(7L))
                .extracting(WorkspaceDomainType::getName).containsExactly("Email", "money");
        assertThat(repository.countByWorkspaceId(7L)).isEqualTo(2);
        assertThat(repository.findByWorkspaceIdAndNameIgnoreCase(7L, "EMAIL")).isPresent();
        assertThat(repository.findByWorkspaceIdAndNameIgnoreCase(7L, "nope")).isEmpty();
        assertThat(repository.findByWorkspaceIdAndNameIgnoreCase(8L, "MONEY")).isEmpty();
    }
}
